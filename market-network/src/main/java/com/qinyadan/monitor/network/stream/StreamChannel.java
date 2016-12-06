package com.qinyadan.monitor.network.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qinyadan.monitor.network.MonitorSocketException;
import com.qinyadan.monitor.network.packet.stream.StreamCode;
import com.qinyadan.monitor.network.packet.stream.StreamPingPacket;
import com.qinyadan.monitor.network.packet.stream.StreamPongPacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public abstract class StreamChannel {

	protected final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final Channel channel;
	private final int streamChannelId;
	private final StreamChannelManager streamChannelManager;

	private final StreamChannelState state;
	private final CountDownLatch openLatch = new CountDownLatch(1);

	private List<StreamChannelStateChangeEventHandler> stateChangeEventHandlers = new CopyOnWriteArrayList<StreamChannelStateChangeEventHandler>();

	public StreamChannel(Channel channel, int streamId, StreamChannelManager streamChannelManager) {
		this.channel = channel;
		this.streamChannelId = streamId;
		this.streamChannelManager = streamChannelManager;

		this.state = new StreamChannelState();
	}

	public void addStateChangeEventHandler(StreamChannelStateChangeEventHandler stateChangeEventHandler) {
		stateChangeEventHandlers.add(stateChangeEventHandler);
	}

	public void setStateChangeEventHandler(List<StreamChannelStateChangeEventHandler> stateChangeEventHandlers) {
		this.stateChangeEventHandlers = stateChangeEventHandlers;
	}

	public List<StreamChannelStateChangeEventHandler> getStateChangeEventHandlers() {
		return new ArrayList<StreamChannelStateChangeEventHandler>(stateChangeEventHandlers);
	}

	boolean changeStateOpen() {
		return changeStateTo(StreamChannelStateCode.OPEN);
	}

	boolean changeStateConnected() {
		try {
			return changeStateTo(StreamChannelStateCode.CONNECTED);
		} finally {
			openLatch.countDown();
		}
	}

	boolean changeStateClose() {
		try {
			if (checkState(StreamChannelStateCode.CLOSED)) {
				return true;
			}
			return changeStateTo(StreamChannelStateCode.CLOSED);
		} finally {
			openLatch.countDown();
		}
	}

	public boolean awaitOpen() {
		try {
			openLatch.await();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	public boolean awaitOpen(long timeoutMillis) {
		try {
			return openLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return false;
	}

	public StreamChannelStateCode getCurrentState() {
		return state.getCurrentState();
	}

	public ChannelFuture sendPing(int requestId) {
		assertState(StreamChannelStateCode.CONNECTED);

		StreamPingPacket packet = new StreamPingPacket(streamChannelId, requestId);
		return this.channel.write(packet);
	}

	public ChannelFuture sendPong(int requestId) {
		assertState(StreamChannelStateCode.CONNECTED);

		StreamPongPacket packet = new StreamPongPacket(streamChannelId, requestId);
		return this.channel.write(packet);
	}

	public void close() {
		this.streamChannelManager.clearResourceAndSendClose(getStreamId(), StreamCode.STATE_CLOSED);
	}

	public Channel getChannel() {
		return channel;
	}

	public int getStreamId() {
		return streamChannelId;
	}

	protected StreamChannelState getState() {
		return state;
	}

	public boolean isServer() {
		if (this instanceof ServerStreamChannel) {
			return true;
		}

		return false;
	}

	void assertState(StreamChannelStateCode stateCode) {
		final StreamChannelStateCode currentCode = getCurrentState();
		if (!checkState(currentCode, stateCode)) {
			throw new MonitorSocketException("expected:<" + stateCode + "> but was:<" + currentCode + ">;");
		}
	}

	boolean checkState(StreamChannelStateCode expectedCode) {
		return checkState(getCurrentState(), expectedCode);
	}

	boolean checkState(StreamChannelStateCode currentCode, StreamChannelStateCode expectedCode) {
		if (currentCode == expectedCode) {
			return true;
		} else {
			return false;
		}
	}

	protected boolean changeStateTo(StreamChannelStateCode nextState) {
		StreamChannelStateCode currentState = getCurrentState();

		boolean isChanged = state.to(currentState, nextState);
		if (!isChanged && (getCurrentState() != StreamChannelStateCode.ILLEGAL_STATE)) {
			changeStateTo(StreamChannelStateCode.ILLEGAL_STATE);
		}

		if (isChanged) {
			for (StreamChannelStateChangeEventHandler handler : stateChangeEventHandlers) {
				try {
					handler.eventPerformed(this, nextState);
				} catch (Exception e) {
					handler.exceptionCaught(this, nextState, e);
				}
			}
		}

		return isChanged;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(this.getClass().getSimpleName());

		sb.append("[Channel:");
		sb.append(channel);

		sb.append(", StreamId:");
		sb.append(getStreamId());

		sb.append(", State:");
		sb.append(getCurrentState());

		sb.append("].");

		return sb.toString();
	}

}
