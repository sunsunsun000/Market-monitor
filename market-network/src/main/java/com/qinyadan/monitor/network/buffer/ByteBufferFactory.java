package com.qinyadan.monitor.network.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface ByteBufferFactory {

	public static final ByteOrder DEFAULT_BYTE_ORDER = ByteOrder.BIG_ENDIAN;
	public static final ByteOrder NATIVE_BYTE_ORDER = ByteOrder.nativeOrder();

	ByteBuffer getBuffer(int capacity);
	ByteBuffer getBuffer(ByteOrder endianness, int capacity);

}
