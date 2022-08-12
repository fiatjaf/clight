import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.collection.mutable.Map
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import com.softwaremill.quicklens._
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._

import codecs._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import crypto.Crypto
import Utils.OnionParseResult

type PaymentStatus = Option[Either[Option[PaymentFailure], ByteVector32]]

sealed trait PaymentFailure
case class FailureOnion(onion: ByteVector) extends PaymentFailure
case class NormalFailureMessage(message: FailureMessage) extends PaymentFailure

case class FromLocal(
    upd: ChannelModifier,

    // this exists to match the htlc incoming and outgoing at the .htlcForwards table
    relatedIncoming: Option[HtlcIdentifier]
)
case class FromRemote(upd: ChannelModifier)

trait ChannelStatus
case object Opening extends ChannelStatus
case object Invoking extends ChannelStatus
case object Active extends ChannelStatus
case object Overriding extends ChannelStatus
case object NotOpened extends ChannelStatus
case object Errored extends ChannelStatus
case object Suspended extends ChannelStatus

class Channel(master: ChannelMaster, peerId: ByteVector) {
  lazy val channelId = Utils.getChannelId(master.node.publicKey, peerId)
  lazy val shortChannelId =
    Utils.getShortChannelId(master.node.publicKey, peerId)

  val htlcResults = Map.empty[ULong, Promise[PaymentStatus]]
  var openingRefundScriptPubKey: Option[ByteVector] = None
  var invoking: Option[ByteVector | LastCrossSignedState] = None
  var state = StateManager(peerId, lcssStored)

  def currentData =
    master.database.data.channels.get(peerId).getOrElse(ChannelData())
  def lcssStored = currentData.lcss
  def status =
    if openingRefundScriptPubKey.isDefined then Opening
    else if invoking.isDefined then Invoking
    else if currentData.lcss.isEmpty then NotOpened
    else if currentData.proposedOverride.isDefined then Overriding
    else if !currentData.localErrors.isEmpty then Errored
    else if currentData.suspended then Suspended
    else Active

  val logger = master.logger.attach.item("peer", peerId.toHex.take(7)).logger

  def sendMessage(
      msg: HostedClientMessage | HostedServerMessage
  ): Future[ujson.Value] =
    master.node.sendCustomMessage(peerId, msg)

  // this function only sends one state_update once for each state
  var stateUpdateSendsTracker = List.empty[StateManager]
  def sendStateUpdate(st: StateManager): Unit = {
    if (!stateUpdateSendsTracker.contains(st)) {
      sendMessage(
        st.lcssNext
          .withCurrentBlockDay(master.currentBlockDay)
          .withLocalSigOfRemote(master.node.privateKey)
          .stateUpdate
      )
      stateUpdateSendsTracker = (st +: stateUpdateSendsTracker).take(3)
    }
  }

  // this tells our upstream to resolve or fail the htlc it is holding
  // (the upstream might be either an actual node (CLN, LND) or another hosted channel)
  def provideHtlcResult(id: ULong, result: PaymentStatus): Unit =
    htlcResults
      .get(id)
      .foreach(_.success(result))

  // a update_add_htlc we've received from the upstream node
  // (for c-lightning this comes from the "htlc_accepted" hook)
  def addHtlc(
      incoming: HtlcIdentifier,
      incomingAmount: MilliSatoshi,
      outgoingAmount: MilliSatoshi,
      paymentHash: ByteVector32,
      cltvExpiry: CltvExpiry,
      nextOnion: ByteVector
  ): Future[PaymentStatus] = {
    val localLogger =
      logger.attach.item(status).item("hash", paymentHash).logger
    localLogger.debug
      .item("incoming", incoming)
      .item("in-amount", incomingAmount)
      .item("out-amount", outgoingAmount)
      .item("cltv", cltvExpiry.toLong)
      .msg("adding HTLC")

    var promise = Promise[PaymentStatus]()

    val preimage = master.database.data.preimages.get(paymentHash)
    if (preimage.isDefined) {
      localLogger.warn
        .item("preimage", preimage.get.toHex)
        .msg("HTLC was already resolved, and we have the preimage right here")
      promise.success(Some(Right(preimage.get)))
    } else if (
      state.lcssNext.incomingHtlcs.exists(_.paymentHash == paymentHash)
    ) {
      // reject htlc as outgoing if it's already incoming, sanity check
      localLogger.err.msg("htlc is already incoming, can't add it as outgoing")
      promise.success(
        Some(
          Left(
            Some(
              NormalFailureMessage(
                IncorrectOrUnknownPaymentDetails(
                  incomingAmount,
                  master.currentBlock.toLong
                )
              )
            )
          )
        )
      )
    } else if (
      master.database.data.htlcForwards
        .get(incoming) == Some(HtlcIdentifier(shortChannelId, _))
    ) {
      // do not add htlc to state if it's already there (otherwise the state will be invalid)
      // this is likely to be hit on reboots as the upstream node will replay pending htlcs on us
      localLogger.debug.msg("won't forward the htlc as it's already there")

      // but we still want to update the callbacks we're keeping track of (because we've rebooted!)
      val htlc = (for {
        outgoing <- master.database.data.htlcForwards.get(incoming)
        entry <- master.database.data.channels.find((p, _) =>
          Utils.getShortChannelId(master.node.publicKey, p) == outgoing.scid
        )
        chandata = entry._2
        htlc <- lcssStored.outgoingHtlcs.find(htlc => htlc.id == outgoing.id)
      } yield htlc).get

      htlcResults += (htlc.id -> promise)
    } else if (status != Active) {
      localLogger.debug
        .item("status", status)
        .msg("can't forward an HTLC to channel that isn't active")
      promise.success(
        Some(
          Left(
            Some(
              NormalFailureMessage(
                TemporaryChannelFailure(getChannelUpdate(false))
              )
            )
          )
        )
      )
    } else {
      // the default case in which we add a new htlc
      // create update_add_htlc based on the prototype we've received
      val htlc = UpdateAddHtlc(
        channelId = channelId,
        id = state.lcssNext.localUpdates.toULong + 1L.toULong,
        paymentHash = paymentHash,
        amountMsat = outgoingAmount,
        cltvExpiry = cltvExpiry,
        onionRoutingPacket = nextOnion
      )

      // prepare modification to new lcss to be our next
      val upd = FromLocal(htlc, Some(incoming))
      val updated = state.addUncommittedUpdate(upd)

      // check a bunch of things, if any fail return a temporary_channel_failure
      val requiredFee = MilliSatoshi(
        master.config.feeBase.toLong + (master.config.feeProportionalMillionths * htlc.amountMsat.toLong / 1000000L)
      )

      if (
        (htlc.cltvExpiry.blockHeight - master.currentBlock).toInt < master.config.cltvExpiryDelta.toInt
      )
        promise.success(
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  IncorrectOrUnknownPaymentDetails(
                    htlc.amountMsat,
                    master.currentBlock.toLong
                  )
                )
              )
            )
          )
        )
      else if (
        (incomingAmount - htlc.amountMsat) < requiredFee ||
        updated.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
        updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
      )
        promise.success(
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  TemporaryChannelFailure(getChannelUpdate(true))
                )
              )
            )
          )
        )
      else {
        // will send update_add_htlc to hosted client
        // and we update the state to include this uncommitted htlc
        state = updated

        // and add to the callbacks we're keeping track of for the upstream node
        htlcResults += (htlc.id -> promise)

        sendMessage(htlc)
          .onComplete {
            case Success(_) =>
              // success here means the client did get our update_add_htlc,
              // so send our signed state_update
              sendStateUpdate(state)
            case Failure(err) => {
              // client is offline and can't take our update_add_htlc,
              // so we fail it on upstream
              // and remove it from the list of uncommitted updates
              localLogger.warn.item(err).msg("failed to send update_add_htlc")
              promise.success(
                Some(
                  Left(
                    Some(
                      NormalFailureMessage(
                        TemporaryChannelFailure(getChannelUpdate(false))
                      )
                    )
                  )
                )
              )
              state = state.removeUncommitedUpdate(upd)
            }
          }
      }
    }

    promise.future
      // just some debug messages
      .andThen { case Success(status) =>
        status match {
          case Some(Right(preimage)) =>
            localLogger.info
              .item("preimage", preimage)
              .msg("routed successfully")
          case Some(Left(Some(FailureOnion(_)))) =>
            localLogger.info.msg("received failure onion")
          case Some(Left(_)) =>
            localLogger.debug.msg("received generic failure")
          case None =>
            localLogger.warn.msg("didn't handle")
        }
      }
  }

  // this tells to our hosted peer we have a failure or success (or if it's still pending -- None -- it does nothing)
  def gotPaymentResult(htlcId: ULong, res: PaymentStatus): Unit = {
    val localLogger = logger.attach
      .item(status)
      .item("htlc", htlcId)
      .item("result", res)
      .logger

    localLogger.debug.item(summary).msg("got payment result")

    res match {
      case None => // payment still pending
      case Some(_)
          if (status != Active && status != Errored && status != Suspended) =>
        // these are the 3 states in which we will still accept results, otherwise do nothing
        // (the other states are effectively states in which no payment could have ever been relayed)
        localLogger.err.msg(
          "not in an acceptable status to accept payment result"
        )
      case Some(Right(preimage)) => {
        // since this comes from the upstream node it is assumed the preimage is valid
        val fulfill = UpdateFulfillHtlc(
          channelId,
          htlcId,
          preimage
        )

        // migrate our state to one containing this uncommitted update
        val upd = FromLocal(fulfill, None)
        state = state.addUncommittedUpdate(upd)

        // save the preimage so if we go offline we can keep trying to send it or resolve manually
        master.database.update { data =>
          data
            .modify(_.preimages)
            .using(_ + (Crypto.sha256(preimage) -> preimage))
        }

        // we will send this immediately to the client and hope he will acknowledge it
        sendMessage(fulfill)
          .onComplete {
            case Success(_) => {
              if (status == Active) sendStateUpdate(state)
            }
            case Failure(err) => {
              // client is offline and can't take our update_fulfill_htlc,
              // so we remove it from the list of uncommitted updates
              // and wait for when the peer becomes online again
              localLogger.warn
                .item(err)
                .msg("failed to send update_fulfill_htlc")
              state = state.removeUncommitedUpdate(upd)
            }
          }
      }
      case Some(Left(failure)) => {
        for {
          htlc <- state.lcssNext.incomingHtlcs.find(_.id == htlcId)
          OnionParseResult(packet, _, sharedSecret) <- Utils
            .parseClientOnion(master.node.privateKey, htlc)
            .toOption
        } yield {
          val fail = failure match {
            case Some(NormalFailureMessage(bo: BadOnion)) =>
              UpdateFailMalformedHtlc(
                htlc.channelId,
                htlc.id,
                bo.onionHash,
                bo.code
              )
            case _ => {
              val reason = failure.getOrElse(
                NormalFailureMessage(
                  TemporaryChannelFailure(getChannelUpdate(true))
                )
              ) match {
                case NormalFailureMessage(fm) =>
                  Sphinx.FailurePacket.create(sharedSecret, fm)
                case FailureOnion(fo) =>
                  // must unwrap here because neither upstream node (CLN) or another hosted channel
                  // won't unwrap whatever packet they got from the next hop
                  Sphinx.FailurePacket.wrap(fo, sharedSecret)
              }

              UpdateFailHtlc(channelId, htlcId, reason)
            }
          }

          // prepare updated state
          val upd = FromLocal(fail, None)
          state = state.addUncommittedUpdate(upd)

          sendMessage(fail)
            .onComplete {
              case Success(_) => {
                if (status == Active) sendStateUpdate(state)
              }
              case Failure(err) => {
                // client is offline and can't take our update_fulfill_htlc,
                // so we remove it from the list of uncommitted updates
                // and wait for when the peer becomes online again
                localLogger.warn
                  .item("err", err)
                  .msg(s"failed to send update_fail_htlc")
                state = state.removeUncommitedUpdate(upd)
              }
            }
        }
      }
    }
  }

  def gotPeerMessage(
      message: HostedClientMessage | HostedServerMessage
  ): Unit = {
    val localLogger = logger.attach.item(status).logger

    localLogger.debug
      .item("state", summary)
      .item("message", message)
      .msg("  <:: got peer message")

    message match {
      // we send branding to anyone really
      case msg: AskBrandingInfo =>
        master.config.branding(localLogger).foreach(sendMessage(_))

      // someone wants a new hosted channel from us
      case msg: InvokeHostedChannel
          if status == NotOpened || status == Suspended => {
        // check chain hash
        if (msg.chainHash != master.chainHash) {
          localLogger.warn
            .item("local", master.chainHash)
            .item("remote", msg.chainHash)
            .msg(s"peer sent InvokeHostedChannel for wrong chain")
          sendMessage(
            Error(
              channelId,
              s"invalid chainHash (local=${master.chainHash} remote=${msg.chainHash})"
            )
          )
        } else {
          // chain hash is ok, proceed
          if (status == NotOpened) {
            if (
              !master.config.requireSecret ||
              master.config.permanentSecrets.contains(msg.secret.toHex) ||
              master.temporarySecrets.contains(msg.secret.toHex)
            ) {
              // save this for the next step (having this also moves us to the Invoking state)
              openingRefundScriptPubKey = Some(msg.refundScriptPubKey)

              // reply saying we accept the invoke and go into Opening state
              sendMessage(master.config.init)

              // remove the temporary secret used, if any
              master.temporarySecrets =
                master.temporarySecrets.filterNot(_ == msg.secret.toHex)
            }
          } else {
            // channel already exists, so send last cross-signed-state
            sendMessage(currentData.lcss)
          }
        }
      }

      // final step of channel open process from the server side
      case msg: StateUpdate if status == Opening => {
        // build last cross-signed state for the beginning of channel
        val lcssInitial = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = openingRefundScriptPubKey.get,
          initHostedChannel = master.config.init,
          blockDay = msg.blockDay,
          localBalanceMsat =
            master.config.channelCapacityMsat - master.config.initialClientBalanceMsat,
          remoteBalanceMsat = master.config.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = List.empty,
          outgoingHtlcs = List.empty,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = msg.localSigOfRemoteLCSS
        )
          .withLocalSigOfRemote(master.node.privateKey)

        // step out of the "opening" state
        openingRefundScriptPubKey = None

        // check if everything is ok
        if ((msg.blockDay - master.currentBlockDay).abs > 1) {
          // we don't get a channel, but also do not send any errors
          localLogger.warn
            .item("local", master.currentBlockDay)
            .item("remote", msg.blockDay)
            .msg("peer sent state_update with wrong blockday")
        } else if (!lcssInitial.verifyRemoteSig(peerId)) {
          // we don't get a channel, but also do not send any errors
          localLogger.warn.msg("peer sent state_update with wrong signature")
        } else {
          // all good, save this channel to the database and consider it opened
          master.database.update { data =>
            data
              .modify(_.channels)
              .using(_ + (peerId -> ChannelData(lcss = lcssInitial)))
          }
          state = state.copy(lcssCurrent = lcssStored)

          // send our signed state update
          System.err.println(
            lastCrossSignedStateCodec
              .encode(lcssInitial)
              .toOption
              .get
              .toByteVector
              .toHex
          )
          sendMessage(
            lcssInitial.withLocalSigOfRemote(master.node.privateKey).stateUpdate
          )

          // send a channel update
          sendMessage(getChannelUpdate(true))
        }
      }

      // we're invoking a channel and the server is ok with it
      case init: InitHostedChannel
          if status == Invoking && invoking.get.isInstanceOf[ByteVector] => {
        // we just accept anything they offer, we don't care
        val spk = invoking.get.asInstanceOf[ByteVector]
        val lcss = LastCrossSignedState(
          isHost = false,
          refundScriptPubKey = spk,
          initHostedChannel = init,
          blockDay = master.currentBlockDay,
          localBalanceMsat = init.initialClientBalanceMsat,
          remoteBalanceMsat =
            init.channelCapacityMsat - init.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = List.empty,
          outgoingHtlcs = List.empty,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = ByteVector64.Zeroes
        )
          .withLocalSigOfRemote(master.node.privateKey)
        invoking = Some(lcss)

        sendMessage(
          StateUpdate(
            blockDay = master.currentBlockDay,
            localUpdates = 0L,
            remoteUpdates = 0L,
            localSigOfRemoteLCSS = lcss.localSigOfRemote
          )
        )
      }

      // final step of channel open process from the client side
      case msg: StateUpdate
          if status == Invoking && invoking.get
            .isInstanceOf[LastCrossSignedState] => {
        // we'll check if lcss they sent is the same we just signed
        val lcssInitial = invoking.get
          .asInstanceOf[LastCrossSignedState]
          .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

        // step out of the "invoking" state
        invoking = None

        if (lcssInitial.verifyRemoteSig(peerId) == false) {
          // their lcss or signature is wrong, stop all here, we won't get a channel
          // but also do not send any errors
          localLogger.warn.msg("peer sent state_update with wrong signature")
        } else {
          // all good, save this channel to the database and consider it opened
          master.database.update { data =>
            data
              .modify(_.channels)
              .using(_ + (peerId -> ChannelData(lcss = lcssInitial)))
          }
          state = state.copy(lcssCurrent = lcssStored)

          // send a channel update
          sendMessage(getChannelUpdate(true))
        }
      }

      // a client is telling us they are online
      case msg: InvokeHostedChannel if status == Active =>
        // after a reconnection our peer won't have any of our current uncommitted updates
        val updatesToReplay = state.uncommittedUpdates
          .filter { case _: FromLocal => true; case _ => false }
        state = state.copy(uncommittedUpdates = List.empty)

        // send the committed state
        sendMessage(lcssStored)
          // replay the uncommitted updates now
          .andThen(_ =>
            // first the fail/fulfill
            updatesToReplay
              .collect {
                case m @ FromLocal(f, _) if !f.isInstanceOf[UpdateAddHtlc] => m
              }
              .foreach { m =>
                state = state.addUncommittedUpdate(m)
                sendMessage(m.upd)
              }
          )
          .andThen(_ =>
            // then the adds
            updatesToReplay
              .collect {
                case m @ FromLocal(add: UpdateAddHtlc, _) => {
                  val newAdd = add.copy(
                    id = state.lcssNext.localUpdates.toULong + 1L.toULong
                  )
                  state = state.addUncommittedUpdate(m.copy(upd = newAdd))
                  sendMessage(newAdd)
                }
              }
          )
          .andThen(_ =>
            // finally send our state_update
            if (updatesToReplay.size > 0) sendStateUpdate(state)
          )

      // if errored, when the client tries to invoke it we return the error
      case _: InvokeHostedChannel if status == Errored =>
        sendMessage(lcssStored)
          .andThen(_ => sendMessage(currentData.localErrors.head.error))

      // if we have an override proposal we return it when the client tries to invoke
      case _: InvokeHostedChannel if status == Overriding =>
        sendMessage(lcssStored)
          .andThen(_ =>
            currentData.localErrors.headOption.map { err =>
              sendMessage(err.error)
            }
          )
          .andThen(_ =>
            sendMessage(
              currentData.proposedOverride.get
                .withLocalSigOfRemote(master.node.privateKey)
                .stateOverride
            )
          )

      // after we've sent our last_cross_signed_state above, the client replies with theirs
      case msg: LastCrossSignedState => {
        val isLocalSigOk = msg.verifyRemoteSig(master.node.publicKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(peerId)

        if (!isLocalSigOk || !isRemoteSigOk) {
          val (err, reason) = if (!isLocalSigOk) {
            (
              Error(
                channelId,
                Error.ERR_HOSTED_WRONG_LOCAL_SIG
              ),
              "peer sent LastCrossSignedState with a signature that isn't ours"
            )
          } else {
            (
              Error(
                channelId,
                Error.ERR_HOSTED_WRONG_REMOTE_SIG
              ),
              "peer sent LastCrossSignedState with an invalid signature"
            )
          }
          localLogger.warn.msg(reason)
          sendMessage(err)
          master.database.update { data =>
            data
              .modify(_.channels.at(peerId).localErrors)
              .using(_ + DetailedError(err, None, reason))
          }
        } else if (status == Active || status == Opening) {
          if (
            (lcssStored.localUpdates + lcssStored.remoteUpdates) <
              (msg.remoteUpdates + msg.localUpdates)
          ) {
            // we are behind. replace our lcss with theirs.
            localLogger.warn
              .item(
                "local",
                s"${lcssStored.localUpdates}/${lcssStored.remoteUpdates}"
              )
              .item("remote", s"${msg.remoteUpdates}/${msg.localUpdates}")
              .msg("peer sent lcss showing that we are behind")

            // step out of the "opening" state
            openingRefundScriptPubKey = None

            // save their lcss here
            master.database.update { data =>
              data
                .modify(_.channels)
                .using(_ + (peerId -> ChannelData(lcss = msg.reverse)))
            }
            state = state.copy(lcssCurrent = lcssStored)
          }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssStored)
          sendMessage(getChannelUpdate(true))

          // investigate the situation of any payments that might be pending
          if (lcssStored.incomingHtlcs.size > 0) {
            val upto: ULong = lcssStored.incomingHtlcs.map(_.id).max
            Timer.timeout(FiniteDuration(3, "seconds")) { () =>
              lcssStored.incomingHtlcs.filter(_.id <= upto).foreach { htlc =>
                // try cached preimages first
                localLogger.debug
                  .item("in", htlc)
                  .msg("checking the outgoing status of pending incoming htlc")
                master.database.data.preimages.get(htlc.paymentHash) match {
                  case Some(preimage) =>
                    gotPaymentResult(htlc.id, Some(Right(preimage)))
                  case None =>
                    localLogger.debug.msg("no preimage")
                    master.database.data.htlcForwards
                      .get(HtlcIdentifier(shortChannelId, htlc.id)) match {
                      case Some(outgoing @ HtlcIdentifier(outScid, outId)) =>
                        // it went to another HC peer, so just wait for it to resolve
                        // (if it had resolved already we would have the resolution on the preimages)
                        {
                          localLogger.debug
                            .item("out", outgoing)
                            .msg("it went to another hc peer")
                        }
                      case None =>
                        // it went to the upstream node, so ask that
                        master.node
                          .inspectOutgoingPayment(
                            HtlcIdentifier(shortChannelId, htlc.id),
                            htlc.paymentHash
                          )
                          .onComplete {
                            case Success(result) =>
                              gotPaymentResult(htlc.id, result)
                            case Failure(err) =>
                              localLogger.err
                                .item(err)
                                .msg("inspectOutgoingPayment failed")
                          }
                    }
                }
              }
            }
          }
        }
      }

      // client is fulfilling an HTLC we've sent
      case msg: UpdateFulfillHtlc if status == Active => {
        // find the htlc
        lcssStored.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            // call our htlc callback so our upstream node is notified
            // we do this to guarantee our money as soon as possible
            provideHtlcResult(htlc.id, Some(Right(msg.paymentPreimage)))

            // keep updated state
            state = state.addUncommittedUpdate(FromRemote(msg))
          }
          case _ => {
            localLogger.warn.msg(
              "client has fulfilled an HTLC we don't know about (or used a wrong preimage)"
            )
          }
        }
      }

      // client is failing an HTLC we've sent
      case msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          if status == Active => {
        msg match {
          case f: UpdateFailHtlc if (f.reason.isEmpty) => {
            // fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            master.database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(
                  _ + DetailedError(
                    err,
                    lcssStored.outgoingHtlcs.find(htlc => htlc.id == f.id),
                    "peer sent UpdateFailHtlc with empty 'reason'"
                  )
                )
            }
          }
          case _ =>
            // keep the updated state
            state = state.addUncommittedUpdate(FromRemote(msg))
        }
      }

      // client is sending an htlc through us
      case htlc: UpdateAddHtlc if status == Active => {
        val updated = state.addUncommittedUpdate(FromRemote(htlc))

        // check if fee and cltv delta etc are correct, otherwise return a failure
        Utils
          .parseClientOnion(master.node.privateKey, htlc)
          .map(_.packet) match {
          case Right(packet: PaymentOnion.ChannelRelayPayload) => {
            if (
              // critical failures, fail the channel
              htlc.amountMsat < packet.amountToForward ||
              updated.lcssNext.incomingHtlcs.size > updated.lcssNext.initHostedChannel.maxAcceptedHtlcs ||
              updated.lcssNext.incomingHtlcs
                .map(_.amountMsat.toLong)
                .sum > updated.lcssNext.initHostedChannel.maxHtlcValueInFlightMsat.toLong ||
              updated.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
              updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
            ) {
              val err = Error(
                channelId,
                Error.ERR_HOSTED_MANUAL_SUSPEND
              )
              sendMessage(err)
              master.database.update { data =>
                data
                  .modify(_.channels.at(peerId).localErrors)
                  .using(
                    _ + DetailedError(
                      err,
                      Some(htlc),
                      "peer sent an htlc that went above some limit"
                    )
                  )
              }
            } else if (
              // non-critical failures, just fail the htlc
              htlc.amountMsat < updated.lcssNext.initHostedChannel.htlcMinimumMsat
            ) {
              scala.concurrent.ExecutionContext.global.execute(() =>
                gotPaymentResult(
                  htlc.id,
                  Some(
                    Left(
                      Some(
                        NormalFailureMessage(
                          TemporaryChannelFailure(getChannelUpdate(true))
                        )
                      )
                    )
                  )
                )
              )
            }

            state = updated
          }
          case Left(_: Exception) => {
            // this means the htlc onion is too garbled, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_MANUAL_SUSPEND
            )
            sendMessage(err)
            master.database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(
                  _ + DetailedError(
                    err,
                    Some(htlc),
                    "peer sent an htlc with a garbled onion"
                  )
                )
            }
          }
          case Left(fail: FailureMessage) => {
            // we have a proper error, so fail this htlc on client
            scala.concurrent.ExecutionContext.global.execute(() =>
              gotPaymentResult(
                htlc.id,
                Some(Left(Some(NormalFailureMessage(fail))))
              )
            )

            // still we first must acknowledge this received htlc, so we keep the updated state
            state = updated
          }

          // decide later what to do here (could be a payment directed to us etc)
          case _ => {
            scala.concurrent.ExecutionContext.global.execute(() =>
              gotPaymentResult(
                htlc.id,
                Some(Left(Some(NormalFailureMessage(TemporaryNodeFailure))))
              )
            )

            // still we first must acknowledge this received htlc, so we keep the updated state
            state = updated
          }
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // question: account for situations in which peer is behind us (ignore?) and for when we're behind?
      //   actually no, these mismatched states will never happen because TCP guarantees the order of messages
      //   -- we must handle them synchronously!
      //   -- if any concurrency is to be added it must be between channels, not inside the same channel.
      case msg: StateUpdate
          if status == Active && !state.uncommittedUpdates.isEmpty && (msg.remoteUpdates > lcssStored.localUpdates || msg.localUpdates > lcssStored.remoteUpdates) => {
        // this will only be triggered if there are uncommitted updates
        // otherwise it will be ignored so the client is free to spam us with
        // valid and up-to-date state_updates and we won't even notice
        localLogger.debug
          .item("local-blockday", state.lcssNext.blockDay)
          .item("remote-blockday", msg.blockDay)
          .item(
            "local-updates",
            s"${state.lcssNext.localUpdates}/${state.lcssNext.remoteUpdates}"
          )
          .item("remote-updates", s"${msg.remoteUpdates}/${msg.localUpdates}")
          .msg("updating our local state after a transition")

        if (msg.blockDay != master.currentBlockDay) {
          localLogger.warn.msg("blockdays are different")
        } else if (msg.localUpdates > state.lcssNext.remoteUpdates) {
          localLogger.debug.msg("we are missing updates from them")
        } else if (msg.remoteUpdates < state.lcssNext.localUpdates) {
          localLogger.debug.msg("they are missing updates from us")
        } else if (
          msg.localUpdates != state.lcssNext.remoteUpdates || msg.remoteUpdates != state.lcssNext.localUpdates
        ) localLogger.debug.msg("peer has a different state than we")
        else {
          // copy the state here so weird things don't happen in the meantime that break it
          // (although this is all synchronous so there shouldn't be any issue, but anyway)
          val currentState = state
          val lcssNext = currentState.lcssNext
            .withCurrentBlockDay(master.currentBlockDay)
            .withLocalSigOfRemote(master.node.privateKey)
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

          localLogger.debug
            .item(
              "updates",
              s"${lcssNext.localUpdates}/${lcssNext.remoteUpdates}"
            )
            .msg("we and the client are now even")
          // verify signature
          if (!lcssNext.verifyRemoteSig(peerId)) {
            // a wrong signature, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            master.database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(
                  _ + DetailedError(
                    err,
                    None,
                    "peer sent a wrong state update or one with a broken signature"
                  )
                )
            }
          } else {
            // grab state before saving the update
            val lcssPrev = lcssStored

            // update new last_cross_signed_state on the database
            localLogger.info.item("lcss", lcssNext).msg("saving on db")
            master.database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(
                  ChannelData(lcss = lcssNext)
                )
                //
                // also remove the links for any htlcs that were relayed from elsewhere to this channel
                // (htlcs that were relayed from this channel to elsewhere will be handled on their side)
                .modify(_.htlcForwards)
                .using(fwd => {
                  val previousOutgoing = lcssPrev.outgoingHtlcs.toSet
                  val nextOutgoing = lcssNext.outgoingHtlcs.toSet
                  val resolved = (previousOutgoing -- nextOutgoing)
                    .map(htlc => HtlcIdentifier(shortChannelId, htlc.id))
                  val remains =
                    fwd.filterNot((_, to) => resolved.contains(to))
                  remains
                })
            }
            state = state.copy(lcssCurrent = lcssStored)

            // time to do some cleaning up -- non-priority
            scala.concurrent.ExecutionContext.global
              .execute(() => master.cleanupPreimages())

            // act on each pending message, relaying them as necessary
            currentState.uncommittedUpdates.foreach {
              // i.e. and fail htlcs if any
              case FromRemote(fail: UpdateFailHtlc) =>
                provideHtlcResult(
                  fail.id,
                  Some(
                    Left(
                      Some(
                        // we don't unwrap it here, it will be unwrapped at gotPaymentResult on the other hosted channel
                        // or it will be unwraped on the node interface layer
                        FailureOnion(fail.reason)
                      )
                    )
                  )
                )
              case FromRemote(fail: UpdateFailMalformedHtlc) =>
                // for c-lightning there is no way to return this correctly,
                // so just return another error for now
                provideHtlcResult(
                  fail.id,
                  Some(
                    Left(
                      Some(
                        NormalFailureMessage(
                          InvalidOnionPayload(0.toULong, 0)
                        )
                      )
                    )
                  )
                )
              case FromRemote(fulfill: UpdateFulfillHtlc) => {
                // we've already relayed this to the upstream node eagerly, so do nothing
              }
              case FromRemote(htlc: UpdateAddHtlc) => {
                // send a payment through the upstream node -- or to another hosted channel
                scala.concurrent.ExecutionContext.global.execute { () =>
                  Utils.parseClientOnion(
                    master.node.privateKey,
                    htlc
                  ) match {
                    case Left(fail) => {
                      // this should never happen
                      localLogger.err.msg(
                        "this should never happen because we had parsed the onion already"
                      )
                      gotPaymentResult(
                        htlc.id,
                        Some(
                          Left(
                            Some(
                              NormalFailureMessage(
                                InvalidOnionPayload(0.toULong, 0)
                              )
                            )
                          )
                        )
                      )
                    }
                    case Right(
                          OnionParseResult(
                            payload: PaymentOnion.FinalTlvPayload,
                            _,
                            _
                          )
                        ) => {
                      // we're receiving the payment? this is weird but possible.
                      // figure out how to handle this later, but we will have to patch
                      // c-lightning so it can allow invoices to be manually settled
                      // (and release the preimage in the process.)
                      // this could also be a trampoline, so when we want to support that
                      // we'll have to look again at how eclair is doing it.
                      localLogger.warn
                        .item("payload", payload)
                        .msg("we're receiving a payment from the client?")

                      scala.concurrent.ExecutionContext.global.execute(() =>
                        gotPaymentResult(
                          htlc.id,
                          Some(
                            Left(
                              Some(NormalFailureMessage(TemporaryNodeFailure))
                            )
                          )
                        )
                      )
                    }
                    case Right(
                          OnionParseResult(
                            payload: PaymentOnion.ChannelRelayPayload,
                            nextOnion: ByteVector,
                            sharedSecret: ByteVector32
                          )
                        ) => {
                      // a payment the client is sending through us to someone else
                      // first check if it's for another hosted channel we may have
                      master.database.data.channels
                        .find((p, _) =>
                          Utils.getShortChannelId(master.node.publicKey, p) ==
                            ShortChannelId(payload.outgoingChannelId)
                        ) match {
                        case Some((targetPeerId, chandata)) => {
                          // it is a local hosted channel
                          // send it to the corresponding channel actor
                          master
                            .getChannel(targetPeerId)
                            .addHtlc(
                              incoming =
                                HtlcIdentifier(shortChannelId, htlc.id),
                              incomingAmount = htlc.amountMsat,
                              outgoingAmount = payload.amountToForward,
                              paymentHash = htlc.paymentHash,
                              cltvExpiry = payload.outgoingCltv,
                              nextOnion = nextOnion
                            )
                            .foreach { res => gotPaymentResult(htlc.id, res) }
                        }
                        case None =>
                          // it is a normal channel on the upstream node
                          // use sendonion
                          master.node
                            .sendOnion(
                              chan = this,
                              htlcId = htlc.id,
                              paymentHash = htlc.paymentHash,
                              firstHop =
                                ShortChannelId(payload.outgoingChannelId),
                              amount = payload.amountToForward,
                              cltvExpiryDelta =
                                payload.outgoingCltv - master.currentBlock,
                              onion = nextOnion
                            )
                      }
                    }
                  }
                }
              }
              case FromLocal(
                    htlc: UpdateAddHtlc,
                    Some(in: HtlcIdentifier)
                  ) => {
                // here we update the database with the mapping between received and sent htlcs
                // (now that we are sure the peer has accepted our update_add_htlc)
                master.database.update { data =>
                  data
                    .modify(_.htlcForwards)
                    .using(
                      _ + (in -> HtlcIdentifier(shortChannelId, htlc.id))
                    )
                }
              }
              case _: FromLocal => {
                // we mostly (except for the action above) do not take any action reactively with
                // updates we originated since we have sent them already before sending our state update
              }
            }

            // send our state update
            sendStateUpdate(currentState)

            // update the state manager state to the new lcss -- i.e. remove all the updates that were
            // committed from the list of uncommitted updates
            // if any  new updates were added in the meantime (shouldn't happen) those won't be affected
            state = state.copy(uncommittedUpdates =
              state.uncommittedUpdates.filterNot(upd =>
                currentState.uncommittedUpdates.exists(_ == upd)
              )
            )

            // clean up htlcResult promises that were already fulfilled
            htlcResults.filterInPlace((_, p) => !p.future.isCompleted)
          }
        }
      }

      // client is accepting our override proposal
      case msg: StateUpdate if status == Overriding => {
        if (
          msg.remoteUpdates == currentData.proposedOverride.get.localUpdates &&
          msg.localUpdates == currentData.proposedOverride.get.remoteUpdates &&
          msg.blockDay == currentData.proposedOverride.get.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = currentData.proposedOverride.get
            .withCurrentBlockDay(master.currentBlockDay)
            .withLocalSigOfRemote(master.node.privateKey)
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

          if (lcss.verifyRemoteSig(peerId)) {
            // update state on the database
            master.database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(ChannelData(lcss = lcss))
            }
            // channel is active again
            state = StateManager(peerId = peerId, lcssCurrent = lcssStored)

            // send our channel policies again just in case
            sendMessage(getChannelUpdate(true))
          }
        }
      }

      // client is sending an error
      case msg: Error => {
        master.database.update { data =>
          data
            .modify(_.channels.at(peerId).remoteErrors)
            .using(_ + msg)

            // add a local error here so this channel is marked as "Errored" for future purposes
            .modify(_.channels.at(peerId).localErrors)
            .using(
              _ + DetailedError(
                Error(
                  channelId,
                  Error.ERR_HOSTED_CLOSED_BY_REMOTE_PEER
                ),
                None,
                "peer sent an error"
              )
            )
        }
      }

      case msg =>
        localLogger.debug.item("msg", msg).msg(s"unhandled")
    }
  }

  def onBlockUpdated(block: BlockHeight): Unit = {
    val expiredOutgoingHtlcs = lcssStored.outgoingHtlcs
      .filter(htlc => htlc.cltvExpiry.toLong < block.toLong)

    if (!expiredOutgoingHtlcs.isEmpty) {
      // if we have any HTLC, we fail the channel
      val err = Error(
        channelId,
        Error.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC
      )
      sendMessage(err)

      // store one error for each htlc failed in this manner
      expiredOutgoingHtlcs.foreach { htlc =>
        master.database.update { data =>
          data
            .modify(_.channels.at(peerId).localErrors)
            .using(
              _ + DetailedError(
                err,
                Some(htlc),
                "outgoing htlc has expired"
              )
            )
        }
      }

      // we also fail them on their upstream node
      expiredOutgoingHtlcs
        .map(out =>
          master.database.data.htlcForwards
            .find((_, to) => to == out)
            .map((from, _) => from)
        )
        .collect { case Some(htlc) => htlc }
        .foreach { in =>
          // resolve htlcs with error for peer
          provideHtlcResult(
            in.id,
            Some(
              Left(
                Some(
                  NormalFailureMessage(
                    PermanentChannelFailure
                  )
                )
              )
            )
          )

        }
    }

    // cleanup uncommitted htlcs that may be pending for so long they're now inviable
    state.uncommittedUpdates.collect {
      case m @ FromLocal(htlc: UpdateAddHtlc, _)
          if (htlc.cltvExpiry.blockHeight - master.currentBlock).toInt < master.config.cltvExpiryDelta.toInt => {
        state = state.removeUncommitedUpdate(m)

        // and fail them upstream
        provideHtlcResult(
          htlc.id,
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  IncorrectOrUnknownPaymentDetails(
                    htlc.amountMsat,
                    master.currentBlock.toLong
                  )
                )
              )
            )
          )
        )
      }
    }
  }

  // opening a channel, as a client, to another hosted channel provider
  def requestHostedChannel(): Future[String] = {
    if (status != NotOpened) {
      Future.failed(
        new Exception(
          "can't open a channel that is already open."
        )
      )
    } else {
      master.node
        .getAddress()
        .map(Bech32.decodeWitnessAddress(_)._3)
        .flatMap(spk => {
          invoking = Some(spk)
          sendMessage(
            InvokeHostedChannel(
              chainHash = master.chainHash,
              refundScriptPubKey = spk,
              secret = ByteVector.empty
            )
          )
        })
        .map(res => res("status").str)
    }
  }

  // proposing to override a channel state, as a host, to the hosted client peer
  def proposeOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    logger.debug
      .item(status)
      .item("new-local-balance", newLocalBalance)
      .msg("proposing override")

    if (status != Errored && status != Overriding) {
      Future.failed(
        new Exception(
          "can't send to this channel since it is not errored or in overriding state."
        )
      )
    } else if (!currentData.lcss.isHost) {
      Future.failed(
        new Exception(
          "can't send to this channel since we are not the hosts."
        )
      )
    } else {
      val lcssOverride = currentData.proposedOverride
        .getOrElse(
          lcssStored
            .copy(
              incomingHtlcs = List.empty,
              outgoingHtlcs = List.empty,
              localUpdates = lcssStored.localUpdates + 1,
              remoteUpdates = lcssStored.remoteUpdates + 1,
              remoteSigOfLocal = ByteVector64.Zeroes,
              localSigOfRemote = ByteVector64.Zeroes
            )
        )
        .copy(
          localBalanceMsat = newLocalBalance,
          remoteBalanceMsat =
            lcssStored.initHostedChannel.channelCapacityMsat - newLocalBalance,
          blockDay = master.currentBlockDay
        )

      master.database.update { data =>
        data
          .modify(_.channels.at(peerId).proposedOverride)
          .setTo(Some(lcssOverride))
      }

      sendMessage(
        lcssOverride
          .withCurrentBlockDay(master.currentBlockDay)
          .withLocalSigOfRemote(master.node.privateKey)
          .stateOverride
      )
        .map((v: ujson.Value) => v("status").str)
    }
  }

  def getChannelUpdate(channelIsUp: Boolean): ChannelUpdate = {
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(master.node.publicKey, peerId),
      isEnabled = channelIsUp
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              master.chainHash,
              shortChannelId,
              timestamp,
              flags,
              master.config.cltvExpiryDelta,
              master.config.htlcMinimumMsat,
              master.config.feeBase,
              master.config.feeProportionalMillionths,
              Some(master.config.channelCapacityMsat),
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, master.node.privateKey)
    ChannelUpdate(
      signature = sig,
      chainHash = master.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = master.config.cltvExpiryDelta,
      htlcMinimumMsat = master.config.htlcMinimumMsat,
      feeBaseMsat = master.config.feeBase,
      feeProportionalMillionths = master.config.feeProportionalMillionths,
      htlcMaximumMsat = Some(master.config.channelCapacityMsat)
    )
  }

  def summary: String = {
    val printable = status match {
      case Opening => s"(${openingRefundScriptPubKey.get.toHex})"
      case Active =>
        s"(lcss=$lcssStored, uncommitted=${state.uncommittedUpdates})"
      case Overriding => s"(${currentData.proposedOverride.get})"
      case Errored    => s"(${currentData.localErrors})"
      case _          => ""
    }

    s"Channel[${peerId.toHex.take(7)}]${status.getClass.getSimpleName}$printable"
  }
}
