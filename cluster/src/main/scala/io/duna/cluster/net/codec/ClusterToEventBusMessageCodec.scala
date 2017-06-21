package io.duna.cluster.net.codec

import java.util

import scala.reflect.runtime.universe.{TypeTag, typeOf, typeTag}

import com.google.protobuf
import io.duna.cluster.internal.message.{ClusterMessage, MessageType}
import io.duna.cluster.util.MessageConversions._
import io.duna.eventbus.message._
import io.duna.s11n.ObjectMapper
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

class ClusterToEventBusMessageCodec extends MessageToMessageCodec[ClusterMessage, Message[_]] {
  import io.duna.reflect._

  override def encode(ctx: ChannelHandlerContext, msg: Message[_], out: util.List[AnyRef]): Unit = {

    val attachment = msg.typeTag.tpe match {
      case tpe if tpe =:= typeOf[Nothing] || tpe =:= typeOf[Unit] || tpe =:= typeOf[Null] => None
      case _ => msg.attachment match {
        case Some(obj) =>
          Some(
            protobuf.ByteString.copyFrom(
              ObjectMapper().serialize(obj.asInstanceOf[AnyRef])(msg.typeTag.asInstanceOf[TypeTag[AnyRef]])
            )
          )
        case None => None
      }
    }

    val attachmentType = msg.typeTag.tpe match {
      case tpe if tpe =:= typeOf[Nothing] || tpe =:= typeOf[Unit] || tpe =:= typeOf[Null] => None
      case _ => Some(msg.typeTag.tpe.portableTypeName)
    }

    val clusterMessage = ClusterMessage(
      msg.clusterMessageType,
      msg.source, msg.target, msg.responseEvent,
      attachment, attachmentType,
      msg.clusterHeaders,
      msg.clusterTransmissionMode,
      msg.clusterSignalType)

    out.add(clusterMessage)
  }

  override def decode(ctx: ChannelHandlerContext, msg: ClusterMessage, out: util.List[AnyRef]): Unit = {
    val attachmentType: Option[TypeTag[_]] = msg.attachmentType match {
      case None => Some(typeTag[Unit])
      case Some(t) => TypeTagCache.get(t)
    }

    val attachment: Option[_] = msg.attachment match {
      case Some(att) if att.isInstanceOf[AnyRef] => ObjectMapper()
        .deserialize[AnyRef](att.asReadOnlyByteBuffer())(
          attachmentType.getOrElse(typeTag[Unit]).asInstanceOf[TypeTag[AnyRef]])
      case None => None
    }

    val transmissionMode = msg.transmissionMode.toEventBus
    val signalType = msg.signalType.toEventBus

    val result: Message[_] = msg.messageType match {
      case MessageType.EVENT =>
         Event[Any](msg.source, msg.target,
           msg.headers.toEventBus,
           attachment, transmissionMode)(attachmentType.get.asInstanceOf[TypeTag[Any]])
      case MessageType.REQUEST =>
        Request(msg.source, msg.target, msg.replyTo,
          msg.headers.toEventBus,
          attachment)
      case MessageType.SIGNAL =>
        Signal(msg.source, msg.target,
          msg.headers.toEventBus,
          transmissionMode, signalType)
      case MessageType.ERROR => attachmentType match {
        case Some(t) if t.tpe <:< typeOf[Throwable] =>
          Error(msg.source, msg.target, msg.replyTo,
            msg.headers.toEventBus,
            attachment.asInstanceOf[Throwable],
            transmissionMode)(attachmentType.asInstanceOf[TypeTag[Throwable]])
        case _ =>
          throw new RuntimeException(s"Invalid error type ${msg.attachmentType.getOrElse("")}")
      }
      case MessageType.Unrecognized(_) => throw new RuntimeException("Unrecognized message type.")
    }

    out.add(result)
  }

}

