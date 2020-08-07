package com.zubr.bot;

/**
 * A utility class of static methods for reading and writing multi-byte fields from SBE messages.
 * @author Bram Sterling
 *
 */
public class SBEUtils {
	private SBEUtils() {}
	
	/**
	 * Reads a little-endian 64-bit value from a position in a {@code byte[]}
	 * @param message source array
	 * @param start index of first byte
	 * @return the value read.
	 */
	public static long parse64(byte[] message, int start) {
		long rval = 0;
		for(int i = start+7; i >= start; i--)
			rval = (rval << 8) | (message[i] & 0xff);
		return rval;
	}
	
	/**
	 * Writes a little-endian 64-bit value to a position in a {@code byte[]}
	 * @param message target array
	 * @param val value to write
	 * @param start index of first byte
	 */
	public static void write64(byte[] message, long val, int start) {
		for(int i = 0; i < 8; i++) {
			message[i+start] = (byte) val;
			val = val >>> 8;
		}
	}
	
	/**
	 * Reads a little-endian 32-bit value from a position in a {@code byte[]}
	 * @param message source array
	 * @param start index of first byte
	 * @return the value read.
	 */
	public static int parse32(byte[] message, int start) {
		int rval = 0;
		for(int i = start+3; i >= start; i--)
			rval = (rval << 8) | (message[i] & 0xff);
		return rval;
	}
	
	/**
	 * Writes a little-endian 32-bit value to a position in a {@code byte[]}
	 * @param message target array
	 * @param val value to write
	 * @param start index of first byte
	 */
	public static void write32(byte[] message, int val, int start) {
		for(int i = 0; i < 4; i++) {
			message[i+start] = (byte) val;
			val = val >>> 8;
		}
	}
	
	/**
	 * Reads a little-endian 16-bit value from a position in a {@code byte[]}
	 * @param message source array
	 * @param start index of first byte
	 * @return the value read.
	 */
	public static int parse16(byte[] message, int start) {
		return (message[start] & 0xff) | ((message[start+1] & 0xff) << 8);
	}

	/**
	 * Writes a little-endian 16-bit value to a position in a {@code byte[]}
	 * @param message target array
	 * @param val value to write. only lower 16 bits are used.
	 * @param start index of first byte
	 */
	public static void write16(byte[] message, int val, int start) {
		message[start] = (byte) (val & 0xff);
		message[start+1] = (byte) ((val >> 8) & 0xff);
	}
}
