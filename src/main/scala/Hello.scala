import java.io.IOException

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel.Streaming
import org.apache.sshd.common.future.{SshFuture, SshFutureListener}
import org.apache.sshd.common.io.IoInputStream
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration.{Duration, _}
import scala.util.{Success, Try}

object Hello extends App {

  implicit class WrapSshFuture[T <: SshFuture[T]](inner: T) extends Future[T] {
    def callback(f: T => Unit): T = {
      inner.addListener(new SshFutureListener[T] {
        override def operationComplete(future: T): Unit = {
          f(future)
        }
      })
    }

    override def onComplete[U](f: (Try[T]) => U)(implicit executor: ExecutionContext): Unit = {
      inner callback { future =>
        f(Success(future))
      }
    }

    override def isCompleted: Boolean = inner.isCompleted

    override def value: Option[Try[T]] = {
      if (inner.isDone) Some(Success(inner))
      else None
    }

    @throws[Exception](classOf[Exception])
    override def result(atMost: Duration)(implicit permit: CanAwait): T = {
      inner.await(atMost._1, atMost._2)
      inner
    }

    @throws[InterruptedException](classOf[InterruptedException])
    @throws[TimeoutException](classOf[TimeoutException])
    override def ready(atMost: Duration)(implicit permit: CanAwait): WrapSshFuture.this.type = {
      inner.await(atMost._1, atMost._2)
      this
    }
  }

  def check(cond: Boolean): Future[Unit] = {
    if (cond) Future.successful(())
    else Future.failed(new Exception("failed."))
  }

  def read[T](in: IoInputStream, init: T)(f: (T, Array[Byte]) => T): Future[T] = {
    val buffer = new ByteArrayBuffer
    in.read(buffer) flatMap { future =>
      val e = future.getException
      if (e == null) {
        val bin = new Array[Byte](future.getBuffer.available)
        future.getBuffer.getRawBytes(bin)
        read(in, f(init, bin))(f)
      } else if (e.isInstanceOf[IOException]) {
        Future.successful(init)
      } else {
        Future.failed(e)
      }
    }
  }

  val client = SshClient.setUpDefaultClient()
  client.start()
  val f = for {
    connection <- client.connect("hogehoge", "54.238.211.72", 22)
    _ <- check(connection.isConnected)
    session = connection.getSession
    _ = session.addPasswordIdentity("hogehoge")
    auth <- session.auth
    _ <- check(auth.isSuccess)
    connection = session.createExecChannel("ls -l")
    _ = connection.setStreaming(Streaming.Async)
    open_fut <- connection.open
    _ <- check(open_fut.isOpened)
    stdout = connection.getAsyncOut
    bytes <- read(stdout, new Array[Byte](0))(_ ++ _)
  } yield {
    println(new String(bytes))
  }
  f onFailure { case e => e.printStackTrace }
  Await.ready(f, 10 seconds)
  client.stop()
}