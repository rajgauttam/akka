/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.dispatch._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util.Helpers.{narrow, narrowSilently}
import se.scalablesolutions.akka.util.Logging

import com.google.protobuf.Message
import java.util.concurrent.TimeUnit

/**
 * Implements the Transactor abstraction. E.g. a transactional actor.
 * <p/>
 * Equivalent to invoking the <code>makeTransactionRequired</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Transactor extends Actor

/**
 * Extend this abstract class to create a remote actor.
 * <p/>
 * Equivalent to invoking the <code>makeRemote(..)</code> method in the body of the <code>Actor</code
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteActor(hostname: String, port: Int) extends Actor {
  protected[akka] def makeRemote(self: Self) = self.get.makeRemote(hostname, port)
}

/**
 * Life-cycle messages for the Actors
 */
@serializable sealed trait LifeCycleMessage
case class HotSwap(code: Option[Actor.Receive]) extends LifeCycleMessage
case class Restart(reason: Throwable) extends LifeCycleMessage
case class Exit(dead: ActorRef, killer: Throwable) extends LifeCycleMessage
case class Link(child: ActorRef) extends LifeCycleMessage
case class Unlink(child: ActorRef) extends LifeCycleMessage
case class UnlinkAndStop(child: ActorRef) extends LifeCycleMessage
case object Kill extends LifeCycleMessage

case object Init extends LifeCycleMessage
case object InitTransactionalState extends LifeCycleMessage
case object Shutdown extends LifeCycleMessage
case class PreRestart(cause: Throwable) extends LifeCycleMessage
case class PostRestart(cause: Throwable) extends LifeCycleMessage


case object ReceiveTimeout

// Exceptions for Actors
class ActorStartException private[akka](message: String) extends RuntimeException(message)
class ActorKilledException private[akka](message: String) extends RuntimeException(message)
class ActorInitializationException private[akka](message: String) extends RuntimeException(message)

/**
 * Actor factory module with factory methods for creating various kinds of Actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Actor extends Logging {
  val TIMEOUT = config.getInt("akka.actor.timeout", 5000)
  val RECEIVE_TIMEOUT = config.getInt("akka.actor.receive.timeout", 30000)
  val SERIALIZE_MESSAGES = config.getBool("akka.actor.serialize-messages", false)

  /**
   * A Receive is a convenience type that defines actor message behavior currently modeled as
   * a PartialFunction[Any, Unit].
   */
  type Receive = PartialFunction[Any, Unit]
  type Self    = Some[ActorRef]

  implicit def selfToActorRef(self : Self) : ActorRef = self.get

  //private[actor] val actorRefInCreation = new scala.util.DynamicVariable[Option[ActorRef]](None)

  /**
   * Creates a Actor.actorOf out of the Actor with type T.
   * <pre>
   *   import Actor._
   *   val actor = actorOf[MyActor]
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf[MyActor].start
   * </pre>
   */
  def actorOf[T <: Actor : Manifest]: ActorRef = new LocalActorRef(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates a Actor.actorOf out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * This function should <b>NOT</b> be used for remote actors.
   * <pre>
   *   import Actor._
   *   val actor = actorOf(new MyActor)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(new MyActor).start
   * </pre>
   */
  def actorOf(factory: => Actor): ActorRef = new LocalActorRef(() => factory)

  /**
   * Use to create an anonymous event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = actor  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def actor(body: Receive): ActorRef =
    actorOf(new Actor() {
      override def init(implicit self: Self) = self.lifeCycle = Some(LifeCycle(Permanent))
      def receive(implicit self: Self): Receive = body
    }).start

  /**
   * Use to create an anonymous transactional event-driven actor.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = transactor  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def transactor(body: Receive): ActorRef =
    actorOf(new Transactor() {
      override def init(implicit self: Self) = self.lifeCycle = Some(LifeCycle(Permanent))
      def receive(implicit self: Self): Receive = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with a 'temporary' life-cycle configuration,
   * which means that if the actor is supervised and dies it will *not* be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * import Actor._
   *
   * val a = temporaryActor  {
   *   case msg => ... // handle message
   * }
   * </pre>
   */
  def temporaryActor(body: Receive): ActorRef =
    actorOf(new Actor() {
      override def init(implicit self: Self) = self.lifeCycle = Some(LifeCycle(Permanent))
      def receive(implicit self: Self) = body
    }).start

  /**
   * Use to create an anonymous event-driven actor with both an init block and a message loop block.
   * <p/>
   * The actor is created with a 'permanent' life-cycle configuration, which means that
   * if the actor is supervised and dies it will be restarted.
   * <p/>
   * The actor is started when created.
   * Example:
   * <pre>
   * val a = Actor.init  {
   *   ... // init stuff
   * } receive   {
   *   case msg => ... // handle message
   * }
   * </pre>
   *
   */
  def init[A](body: => Unit) = {
    def handler[A](body: => Unit) = new {
      def receive(handler: Receive) =
        actorOf(new Actor() {
          override def init(implicit self: Self) = self.lifeCycle = Some(LifeCycle(Permanent))
          body
          def receive(implicit self: Self) = handler
        }).start
    }
    handler(body)
  }

  /**
   * Use to spawn out a block of code in an event-driven actor. Will shut actor down when
   * the block has been executed.
   * <p/>
   * NOTE: If used from within an Actor then has to be qualified with 'Actor.spawn' since
   * there is a method 'spawn[ActorType]' in the Actor trait already.
   * Example:
   * <pre>
   * import Actor._
   *
   * spawn  {
   *   ... // do stuff
   * }
   * </pre>
   */
  def spawn(body: => Unit): Unit = {
    case object Spawn
    actorOf(new Actor() {
      def receive(implicit self: Self) = {
        case Spawn => body; self.stop
      }
    }).start ! Spawn
  }

  /**
   * Implicitly converts the given Option[Any] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   */
  implicit def toAnyOptionAsTypedOption(anyOption: Option[Any]) = new AnyOptionAsTypedOption(anyOption)
  
  /**
   * A catch-all for LifeCycleMessages that are leaking through
   */
   private[Actor] val ignoreLifeCycles : Receive = { case ignore: LifeCycleMessage => }
}

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 * <p/>
 * An actor has a well-defined (non-cyclic) life-cycle.
 * <pre>
 * => NEW (newly created actor) - can't receive messages (yet)
 *     => STARTED (when 'start' is invoked) - can receive messages
 *         => SHUT DOWN (when 'exit' is invoked) - can't do anything
 * </pre>
 *
 * <p/>
 * The Actor's API is available in the 'self' member variable.
 *
 * <p/>
 * Here you find functions like:
 *   - !, !!, !!! and forward
 *   - link, unlink, startLink, spawnLink etc
 *   - makeTransactional, makeRemote etc.
 *   - start, stop
 *   - etc.
 *
 * <p/>
 * Here you also find fields like
 *   - dispatcher = ...
 *   - id = ...
 *   - lifeCycle = ...
 *   - faultHandler = ...
 *   - trapExit = ...
 *   - etc.
 *
 * <p/>
 * This means that to use them you have to prefix them with 'self', like this: <tt>self ! Message</tt>
 *
 * However, for convenience you can import these functions and fields like below, which will allow you do
 * drop the 'self' prefix:
 * <pre>
 * class MyActor extends Actor  {
 *   import self._
 *   id = ...
 *   dispatcher = ...
 *   spawnLink[OtherActor]
 *   ...
 * }
 * </pre>
 *
 * <p/>
 * The Actor trait also has a 'log' member field that can be used for logging within the Actor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor extends Logging {
  /**
   * Type alias because traits cannot have companion objects.
   */
  type Receive = Actor.Receive
  type Self    = Actor.Self

  import Actor.{selfToActorRef,ignoreLifeCycles}
  /**
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by concrete actor class.
   * <p/>
   * Example code:
   * <pre>
   *   def receive =  {
   *     case Ping =&gt;
   *       log.info("got a 'Ping' message")
   *       self.reply("pong")
   *
   *     case OneWay =&gt;
   *       log.info("got a 'OneWay' message")
   *
   *     case unknown =&gt;
   *       log.warning("unknown message [%s], ignoring", unknown)
   * }
   * </pre>
   */
  protected def receive(implicit self: Self): Receive
  
  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] def base(implicit self: Self): Receive = try {

    if(timeoutActor.isDefined) {
      Scheduler.unschedule(timeoutActor.get)
      timeoutActor = None
      log.debug("Timeout canceled")
    }

    systemLifeCycles orElse (self.hotswap getOrElse receive) orElse ignoreLifeCycles
  } catch {
    case e: NullPointerException => throw new IllegalStateException(
      "The 'self' ActorRef reference for [" + getClass.getName + "] is NULL, error in the ActorRef initialization process.")
  }
  
  @volatile protected[akka] var timeoutActor: Option[ActorRef] = None

  private val systemLifeCycles(self: Self): Receive = {
    case HotSwap(code) => {
      self.hotswap = code
      if (self.isDefinedAt(ReceiveTimeout)) {
        log.debug("Scheduling timeout for Actor [" + toString + "]")
        timeoutActor = Some(Scheduler.scheduleOnce(self, ReceiveTimeout, self.receiveTimeout, TimeUnit.MILLISECONDS))
      }
    }
    case Restart(reason) => self.restart(reason)
    case Exit(dead, reason) => self.handleTrapExit(dead, reason)
    case Link(child) => self.link(child)
    case Unlink(child) => self.unlink(child)
    case UnlinkAndStop(child) => self.unlink(child); child.stop
    case Kill => throw new ActorKilledException("Actor [" + toString + "] was killed by a Kill message")
  }
}

private[actor] class AnyOptionAsTypedOption(anyOption: Option[Any]) {

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will throw a ClassCastException
   * if the actual type is not assignable from the given one.
   */
  def as[T]: Option[T] = narrow[T](anyOption)

  /**
   * Convenience helper to cast the given Option of Any to an Option of the given type. Will swallow a possible
   * ClassCastException and return None in that case.
   */
  def asSilently[T: Manifest]: Option[T] = narrowSilently[T](anyOption)
}
