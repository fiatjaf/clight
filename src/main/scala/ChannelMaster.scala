import scala.scalanative.unsigned._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import castor.Context.Simple.global
import scodec.bits.ByteVector
import scodec.{DecodeResult}

import codecs._
import crypto.Crypto

type HasFailed = Option[FailureMessage | ByteVector]
type Preimage = ByteVector32
type UpstreamPaymentStatus = Option[Either[HasFailed, Preimage]]

sealed trait PaymentFailure
case class FailureOnion(onion: ByteVector) extends PaymentFailure
case class FailureCode(code: String) extends PaymentFailure

type PaymentPreimage = ByteVector32
type HTLCResult =
  Option[Either[PaymentFailure, PaymentPreimage]]

case class FromLocal(upd: ChannelModifier)
case class FromRemote(upd: ChannelModifier)

object ChannelMaster {
  val servers = mutable.Map.empty[ByteVector, ChannelServer]
  val clients = mutable.Map.empty[ByteVector, ChannelClient]
  def getChannelServer(peerId: ByteVector): ChannelServer = {
    servers.getOrElseUpdate(peerId, { new ChannelServer(peerId) })
  }
  def getChannelClient(peerId: ByteVector): ChannelClient = {
    clients.getOrElseUpdate(peerId, { new ChannelClient(peerId) })
  }

  def all: Map[ByteVector, ChannelData] = Database.data.channels

  def channelsJSON: ujson.Arr = {
    val mapHtlc = (htlc: UpdateAddHtlc) =>
      ujson.Obj(
        "id" -> htlc.id.toLong.toInt,
        "amount" -> htlc.amountMsat.toLong.toInt,
        "hash" -> htlc.paymentHash.toHex,
        "cltv" -> htlc.cltvExpiry.toLong.toInt
      )

    ujson.Arr.from(
      all.toList.map((peerId, chandata) =>
        ujson.Obj(
          "peer_id" -> peerId.toHex,
          "channel_id" -> ChanTools.getChannelId(peerId).toHex,
          "short_channel_id" -> ChanTools.getShortChannelId(peerId).toString,
          "status" -> ujson.Obj(
            "blockday" -> chandata.lcss.blockDay.toInt,
            "active" -> chandata.isActive,
            "error" -> chandata.error
              .map(err => ujson.Str(err.description))
              .getOrElse(ujson.Null),
            "is_host" -> chandata.lcss.isHost
          ),
          "balance" -> ujson.Obj(
            "total" -> chandata.lcss.initHostedChannel.channelCapacityMsat.toLong.toInt,
            "local" -> chandata.lcss.localBalanceMsat.toLong.toInt,
            "remote" -> chandata.lcss.remoteBalanceMsat.toLong.toInt
          ),
          "incoming_htlcs" -> ujson.Arr.from(
            chandata.lcss.incomingHtlcs.map(mapHtlc)
          ),
          "outgoing_htlcs" -> ujson.Arr.from(
            chandata.lcss.outgoingHtlcs.map(mapHtlc)
          )
        )
      )
    )
  }
}

object ChanTools {
  def getChannelId(peerId: ByteVector): ByteVector32 =
    Utils.getChannelId(Main.node.ourPubKey, peerId)

  def getShortChannelId(peerId: ByteVector): ShortChannelId =
    Utils.getShortChannelId(
      Main.node.ourPubKey,
      peerId
    )

  def makeChannelUpdate(peerId: ByteVector): ChannelUpdate = {
    val shortChannelId = getShortChannelId(peerId)
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(Main.node.ourPubKey, peerId),
      isEnabled = true
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              Main.chainHash,
              shortChannelId,
              timestamp,
              flags,
              Main.config.cltvExpiryDelta,
              Main.ourInit.htlcMinimumMsat,
              Main.config.feeBase,
              Main.config.feeProportionalMillionths,
              Some(Main.ourInit.channelCapacityMsat),
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, Main.node.getPrivateKey())
    ChannelUpdate(
      signature = sig,
      chainHash = Main.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = Main.config.cltvExpiryDelta,
      htlcMinimumMsat = Main.ourInit.htlcMinimumMsat,
      feeBaseMsat = Main.config.feeBase,
      feeProportionalMillionths = Main.config.feeProportionalMillionths,
      htlcMaximumMsat = Some(Main.ourInit.channelCapacityMsat)
    )
  }

  case class OnionParseResult(
      packet: PaymentOnion.PaymentPacket,
      nextOnion: ByteVector,
      sharedSecret: ByteVector32
  )

  def parseClientOnion(add: UpdateAddHtlc): Either[
    Exception | FailureMessage,
    OnionParseResult
  ] =
    PaymentOnionCodecs.paymentOnionPacketCodec
      .decode(add.onionRoutingPacket.toBitVector)
      .toEither
      .map(_.value) match {
      case Left(err) =>
        // return something here that indicates we must fail this channel
        Left(Exception("unparseable onion"))
      case Right(onion) =>
        Sphinx.peel(
          Main.node.getPrivateKey(),
          Some(add.paymentHash),
          onion
        ) match {
          case Left(badOnion) => Left(badOnion)
          case Right(
                dp @ Sphinx.DecryptedPacket(payload, nextPacket, sharedSecret)
              ) => {
            val decodedOurOnion = PaymentOnionCodecs
              .paymentOnionPerHopPayloadCodec(dp.isLastPacket)
              .decode(payload.bits)
              .toEither
              .map(_.value)
            val encodedNextOnion = PaymentOnionCodecs.paymentOnionPacketCodec
              .encode(nextPacket)
              .toEither
              .map(_.toByteVector)

            (decodedOurOnion, encodedNextOnion) match {
              case (Right(packet), Right(nextOnion)) =>
                Right(OnionParseResult(packet, nextOnion, sharedSecret))
              case (Left(e: OnionRoutingCodecs.MissingRequiredTlv), _) =>
                Left(e.failureMessage)
              case _ => Left(InvalidOnionPayload(0.toULong, 0))
            }
          }
        }
    }
}
