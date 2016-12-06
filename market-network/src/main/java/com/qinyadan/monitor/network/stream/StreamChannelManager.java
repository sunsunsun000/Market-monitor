package com.qinyadan.monitor.network.stream;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qinyadan.monitor.network.MonitorSocketException;
import com.qinyadan.monitor.network.packet.PacketType;
import com.qinyadan.monitor.network.packet.stream.StreamClosePacket;
import com.qinyadan.monitor.network.packet.stream.StreamCode;
import com.qinyadan.monitor.network.packet.stream.StreamCreateFailPacket;
import com.qinyadan.monitor.network.packet.stream.StreamCreatePacket;
import com.qinyadan.monitor.network.packet.stream.StreamCreateSuccessPacket;
import com.qinyadan.monitor.network.packet.stream.StreamPacket;
import com.qinyadan.monitor.network.packet.stream.StreamPingPacket;
import com.qinyadan.monitor.network.packet.stream.StreamResponsePacket;
import com.qinyadan.monitor.network.util.AssertUtils;
import com.qinyadan.monitor.network.util.IDGenerator;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public class StreamChannelManager {

	private static final LoggingStreamChannelStateChangeEventHandler LOGGING_STATE_CHANGE_HANDLER = new LoggingStreamChannelStateChangeEventHandler();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final Channel channel;

	private final IDGenerator idGenerator;

	private final ServerStreamChannelMessageListener streamChannelMessageListener;

	private final ConcurrentMap<Integer, StreamChannelContext> channelMap = new ConcurrentHashMap<Integer, StreamChannelContext>();

	public StreamChannelManager(Channel channel ,IDGenerator idGenerator) {
		this(channel,idGenerator, DisabledServerStreamChannelMessageListener.INSTANCE);
	}

	public StreamChannelManager(Channel channel,IDGenerator idGenerator,
			ServerStreamChannelMessageListener serverStreamChannelMessageListener) {
		AssertUtils.assertNotNull(idGenerator, "IDGenerator may not be null.");
		AssertUtils.assertNotNull(serverStreamChannelMessageListener,
				"ServerStreamChannelMessageListener may not be null.");
		this.channel = channel;
		this.idGenerator = idGenerator;
		this.streamChannelMessageListener = serverStreamChannelMessageListener;
	}

	public void close() {
		Set<Integer> keySet = channelMap.keySet();

		for (Integer key : keySet) {
			clearResourceAndSendClose(key, StreamCode.STATE_CLOSED);
		}
	}

	public ClientStreamChannelContext openStream(byte[] payload, ClientStreamChannelMessageListener messageListener) {
		return openStream(payload, messageListener, LOGGING_STATE_CHANGE_HANDLER);
	}

	public ClientStreamChannelContext openStream(byte[] payload, ClientStreamChannelMessageListener messageListener,
			StreamChannelStateChangeEventHandler<ClientStreamChannel> stateChangeListener) {
		logger.info("Open streamChannel initialization started. Channel:{} ", channel);

		final int streamChannelId = idGenerator.generate();

		ClientStreamChannel newStreamChannel = new ClientStreamChannel(channel, streamChannelId, this);

		if (stateChangeListener != null) {
			newStreamChannel.addStateChangeEventHandler(stateChangeListener);
		} else {
			newStreamChannel.addStateChangeEventHandler(LOGGING_STATE_CHANGE_HANDLER);
		}
		newStreamChannel.changeStateOpen();

		ClientStreamChannelContext newStreamChannelContext = new ClientStreamChannelContext(newStreamChannel,
				messageListener);

		StreamChannelContext old = channelMap.put(streamChannelId, newStreamChannelContext);
		if (old != null) {
			throw new MonitorSocketException(
					"already streamChannelId exist:" + streamChannelId + " streamChannel:" + old);
		}

		// the order of below code is very important.
		newStreamChannel.changeStateConnectAwait();
		newStreamChannel.sendCreate(payload);

		newStreamChannel.awaitOpen(3000);

		if (newStreamChannel.checkState(StreamChannelStateCode.CONNECTED)) {
			logger.info("Open streamChannel initialization completed. Channel:{}, StreamChannelContext:{} ", channel,
					newStreamChannelContext);
		} else {
			newStreamChannel.changeStateClose();
			channelMap.remove(streamChannelId);
			newStreamChannelContext
					.setCreateFailPacket(new StreamCreateFailPacket(streamChannelId, StreamCode.CONNECTION_TIMEOUT));
		}
		return newStreamChannelContext;
	}

	public void messageReceived(StreamPacket packet) {
		final int streamChannelId = packet.getStreamChannelId();
		final short packetType = packet.getPacketType();

		logger.debug("StreamChannel message received. (Channel:{}, StreamId:{}, Packet:{}).", channel, streamChannelId,
				packet);

		if (PacketType.APPLICATION_STREAM_CREATE == packetType) {
			handleCreate((StreamCreatePacket) packet);
			return;
		}

		StreamChannelContext context = findStreamChannel(streamChannelId);
		if (context == null) {
			if (!(PacketType.APPLICATION_STREAM_CLOSE == packetType)) {
				clearResourceAndSendClose(streamChannelId, StreamCode.ID_NOT_FOUND);
			}
		} else {
			if (isServerStreamChannelContext(context)) {
				messageReceived((ServerStreamChannelContext) context, packet);
			} else if (isClientStreamChannelContext(context)) {
				messageReceived((ClientStreamChannelContext) context, packet);
			} else {
				clearResourceAndSendClose(streamChannelId, StreamCode.UNKNWON_ERROR);
			}
		}
	}

	private void messageReceived(ServerStreamChannelContext context, StreamPacket packet) {
		final short packetType = packet.getPacketType();
		final int streamChannelId = packet.getStreamChannelId();

		switch (packetType) {
		case PacketType.APPLICATION_STREAM_CLOSE:
			handleStreamClose(context, (StreamClosePacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_PING:
			handlePing(context, (StreamPingPacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_PONG:
			// handlePong((StreamPongPacket) packet);
			break;
		default:
			clearResourceAndSendClose(streamChannelId, StreamCode.PACKET_UNKNOWN);
			logger.info("Unknown StreamPacket received Channel:{}, StreamId:{}, Packet;{}.", channel, streamChannelId,
					packet);
		}
	}

	private void messageReceived(ClientStreamChannelContext context, StreamPacket packet) {
		final short packetType = packet.getPacketType();
		final int streamChannelId = packet.getStreamChannelId();

		switch (packetType) {
		case PacketType.APPLICATION_STREAM_CREATE_SUCCESS:
			handleCreateSuccess(context, (StreamCreateSuccessPacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_CREATE_FAIL:
			handleCreateFail(context, (StreamCreateFailPacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_RESPONSE:
			handleStreamResponse(context, (StreamResponsePacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_CLOSE:
			handleStreamClose(context, (StreamClosePacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_PING:
			handlePing(context, (StreamPingPacket) packet);
			break;
		case PacketType.APPLICATION_STREAM_PONG:
			// handlePong((StreamPongPacket) packet);
			break;
		default:
			clearResourceAndSendClose(streamChannelId, StreamCode.PACKET_UNKNOWN);
			logger.info("Unknown StreamPacket received Channel:{}, StreamId:{}, Packet;{}.", channel, streamChannelId,
					packet);
		}
	}

	private void handleCreate(StreamCreatePacket packet) {
		final int streamChannelId = packet.getStreamChannelId();

		StreamCode code = StreamCode.OK;
		ServerStreamChannel streamChannel = new ServerStreamChannel(this.channel, streamChannelId, this);
		ServerStreamChannelContext streamChannelContext = new ServerStreamChannelContext(streamChannel);

		code = registerStreamChannel(streamChannelContext);

		if (code == StreamCode.OK) {
			code = streamChannelMessageListener.handleStreamCreate(streamChannelContext, (StreamCreatePacket) packet);

			if (code == StreamCode.OK) {
				streamChannel.changeStateConnected();
				streamChannel.sendCreateSuccess();
			}
		}

		if (code != StreamCode.OK) {
			clearResourceAndSendCreateFail(streamChannelId, code);
		}
	}

	private StreamCode registerStreamChannel(ServerStreamChannelContext streamChannelContext) {
		int streamChannelId = streamChannelContext.getStreamId();
		ServerStreamChannel streamChannel = streamChannelContext.getStreamChannel();
		streamChannel.changeStateOpen();

		if (channelMap.putIfAbsent(streamChannelId, streamChannelContext) != null) {
			streamChannel.changeStateClose();
			return StreamCode.ID_DUPLICATED;
		}

		if (!streamChannel.changeStateConnectArrived()) {
			streamChannel.changeStateClose();
			channelMap.remove(streamChannelId);

			return StreamCode.STATE_ERROR;
		}

		return StreamCode.OK;
	}

	private void handleCreateSuccess(ClientStreamChannelContext streamChannelContext,
			StreamCreateSuccessPacket packet) {
		StreamChannel streamChannel = streamChannelContext.getStreamChannel();
		streamChannel.changeStateConnected();
	}

	private void handleCreateFail(ClientStreamChannelContext streamChannelContext, StreamCreateFailPacket packet) {
		streamChannelContext.setCreateFailPacket(packet);
		clearStreamChannelResource(streamChannelContext.getStreamId());
	}

	private void handleStreamResponse(ClientStreamChannelContext context, StreamResponsePacket packet) {
		int streamChannelId = packet.getStreamChannelId();

		StreamChannel streamChannel = context.getStreamChannel();

		StreamChannelStateCode currentCode = streamChannel.getCurrentState();

		if (StreamChannelStateCode.CONNECTED == currentCode) {
			context.getClientStreamChannelMessageListener().handleStreamData(context, packet);
		} else if (StreamChannelStateCode.CONNECT_AWAIT == currentCode) {
			// may happen in the timing
		} else {
			clearResourceAndSendClose(streamChannelId, StreamCode.STATE_NOT_CONNECTED);
		}
	}

	private void handleStreamClose(ClientStreamChannelContext context, StreamClosePacket packet) {
		context.getClientStreamChannelMessageListener().handleStreamClose(context, (StreamClosePacket) packet);
		clearStreamChannelResource(context.getStreamId());
	}

	private void handleStreamClose(ServerStreamChannelContext context, StreamClosePacket packet) {
		streamChannelMessageListener.handleStreamClose(context, packet);
		clearStreamChannelResource(context.getStreamId());
	}

	private void handlePing(StreamChannelContext streamChannelContext, StreamPingPacket packet) {
		int streamChannelId = packet.getStreamChannelId();

		StreamChannel streamChannel = streamChannelContext.getStreamChannel();
		if (!streamChannel.checkState(StreamChannelStateCode.CONNECTED)) {
			clearResourceAndSendClose(streamChannelId, StreamCode.STATE_NOT_CONNECTED);
			return;
		}

		streamChannel.sendPong(packet.getRequestId());
	}

	public StreamChannelContext findStreamChannel(int channelId) {
		StreamChannelContext streamChannelContext = this.channelMap.get(channelId);

		return streamChannelContext;
	}

	private ChannelFuture clearResourceAndSendCreateFail(int streamChannelId, StreamCode code) {
		clearStreamChannelResource(streamChannelId);
		return sendCreateFail(streamChannelId, code);
	}

	protected ChannelFuture clearResourceAndSendClose(int streamChannelId, StreamCode code) {
		clearStreamChannelResource(streamChannelId);
		return sendClose(streamChannelId, code);
	}

	private void clearStreamChannelResource(int streamId) {
		StreamChannelContext streamChannelContext = channelMap.remove(streamId);

		if (streamChannelContext != null) {
			streamChannelContext.getStreamChannel().changeStateClose();
		}
	}

	private ChannelFuture sendCreateFail(int streamChannelId, StreamCode code) {
		StreamCreateFailPacket packet = new StreamCreateFailPacket(streamChannelId, code);
		return this.channel.write(packet);
	}

	private ChannelFuture sendClose(int streamChannelId, StreamCode code) {
		if (channel.isOpen()) {
			StreamClosePacket packet = new StreamClosePacket(streamChannelId, code);
			return this.channel.write(packet);
		} else {
			return null;
		}
	}

	private boolean isServerStreamChannelContext(StreamChannelContext context) {
		if (context == null || !(context instanceof ServerStreamChannelContext)) {
			return false;
		}
		return true;
	}

	private boolean isClientStreamChannelContext(StreamChannelContext context) {
		if (context == null || !(context instanceof ClientStreamChannelContext)) {
			return false;
		}
		return true;
	}

	public boolean isSupportServerMode() {
		return streamChannelMessageListener != DisabledServerStreamChannelMessageListener.INSTANCE;
	}

}