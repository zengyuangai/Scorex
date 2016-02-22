package scorex.consensus.mining

import akka.actor.Actor
import scorex.app.Application
import scorex.block.Block
import scorex.consensus.mining.Miner._
import scorex.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class Miner(application: Application) extends Actor with ScorexLogging {

  // BlockGenerator is trying to generate a new block every $blockGenerationDelay. Should be 0 for PoW consensus model.
  val blockGenerationDelay = application.settings.blockGenerationDelay
  var lastTryTime = System.currentTimeMillis()
  var stopped = false

  private def scheduleAGuess(delay: Option[FiniteDuration] = None): Unit =
    if (!stopped) context.system.scheduler.scheduleOnce(delay.getOrElse(blockGenerationDelay), self, GuessABlock)

  scheduleAGuess(Some(0.millis))

  override def receive: Receive = {
    case GuessABlock =>
      stopped = false
      tryToGenerateABlock()

    case GetLastGenerationTime =>
      sender ! LastGenerationTime(lastTryTime)

    case Stop =>
      stopped = true
  }

  def tryToGenerateABlock(): Unit = {
    implicit val transactionalModule = application.transactionModule
    lastTryTime = System.currentTimeMillis()

    if (blockGenerationDelay > 500.milliseconds) log.info("Trying to generate a new block")
    val accounts = application.wallet.privateKeyAccounts()
    application.consensusModule.generateNextBlocks(accounts)(application.transactionModule) onComplete {
      case Success(blocks: Seq[Block]) =>
        if (blocks.nonEmpty) {
          val bestBlock = blocks.maxBy(application.consensusModule.blockScore)
          application.historySynchronizer ! bestBlock
        }
        scheduleAGuess()
      case Failure(ex) =>
        log.error(s"Failed to generate new block: ${ex.getMessage}")
    }
  }

}

object Miner {

  case object Stop

  case object GuessABlock

  case object GetLastGenerationTime

  case class LastGenerationTime(time: Long)

}