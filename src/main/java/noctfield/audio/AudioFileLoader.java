package noctfield.audio;

// M155: Audio file loading utility for NOCTFIELD v2
// Audio files are CC BY 4.0 -- sourced from freesound.org by klankbeeld
// Credit: klankbeeld on Freesound (CC BY 4.0) -- https://freesound.org/people/klankbeeld/

import java.io.RandomAccessFile;
import java.nio.ShortBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.stb.STBVorbis;

import static org.lwjgl.openal.AL10.*;

public class AudioFileLoader {

    static final int TARGET_RATE = 22050;
    static final int MAX_SECONDS = 90;
    static final int MAX_SAMPLES = TARGET_RATE * MAX_SECONDS;

    // ---------------------------------------------------------------- WAV

    /**
     * Load a WAV file into an OpenAL buffer (mono 16-bit 22050Hz, max 90s).
     * Handles 8/16/24/32-bit PCM, any sample rate, mono or stereo.
     * Does NOT use javax.sound.sampled — parses the WAV directly so 24-bit works.
     * Returns 0 on failure (game continues with procedural audio).
     */
    public static int loadWav(String path) {
        try {
            RandomAccessFile raf = new RandomAccessFile(path, "r");

            // Read RIFF/WAVE header
            byte[] riff = new byte[12];
            raf.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F')
                throw new Exception("Not a RIFF file");
            if (riff[8] != 'W' || riff[9] != 'A' || riff[10] != 'V' || riff[11] != 'E')
                throw new Exception("Not WAVE format");

            // Scan subchunks to find fmt + data
            int  channels = 2, srcRate = 48000, bitsPerSample = 16;
            long dataOffset = -1, dataSize = 0;
            byte[] chunkHdr = new byte[8];

            while (raf.getFilePointer() < raf.length() - 8) {
                raf.readFully(chunkHdr);
                String id   = new String(chunkHdr, 0, 4);
                long   size = Integer.toUnsignedLong(readIntLE(chunkHdr, 4));

                if (id.equals("fmt ")) {
                    int toRead = (int) Math.min(size, 40);
                    byte[] fmt = new byte[toRead];
                    raf.readFully(fmt);
                    // fmt[0-1]=audioFormat, [2-3]=channels, [4-7]=sampleRate, [14-15]=bitsPerSample
                    channels      = readShortLE(fmt, 2);
                    srcRate       = readIntLE(fmt, 4);
                    bitsPerSample = readShortLE(fmt, 14);
                    long remaining = size - toRead;
                    if (remaining > 0) raf.skipBytes((int) Math.min(remaining, raf.length() - raf.getFilePointer()));
                } else if (id.equals("data")) {
                    dataOffset = raf.getFilePointer();
                    dataSize   = size;
                    break;
                } else {
                    raf.skipBytes((int) Math.min(size, raf.length() - raf.getFilePointer()));
                }
                if ((size & 1) != 0) raf.skipBytes(1);
            }

            if (dataOffset < 0) { raf.close(); throw new Exception("No data chunk found"); }

            int  bytesPerSample = bitsPerSample / 8;
            int  frameSize      = channels * bytesPerSample;
            long maxBytes       = (long) MAX_SECONDS * srcRate * frameSize;
            int  bytesToRead    = (int) Math.min(dataSize, maxBytes);

            raf.seek(dataOffset);
            byte[] raw = new byte[bytesToRead];
            raf.readFully(raw);
            raf.close();

            int totalFrames    = bytesToRead / frameSize;
            int dstSampleCount = (int) Math.min((long) totalFrames * TARGET_RATE / srcRate, MAX_SAMPLES);
            ShortBuffer sb     = MemoryUtil.memAllocShort(dstSampleCount);

            for (int i = 0; i < dstSampleCount; i++) {
                int srcFrame = (int) ((long) i * srcRate / TARGET_RATE);
                if (srcFrame >= totalFrames) { sb.put(i, (short) 0); continue; }
                long sum = 0;
                for (int c = 0; c < channels; c++) {
                    int off = srcFrame * frameSize + c * bytesPerSample;
                    sum += readSampleAs16(raw, off, bytesPerSample);
                }
                sb.put(i, (short) (sum / channels));
            }

            int bufId = alGenBuffers();
            alBufferData(bufId, AL_FORMAT_MONO16, sb, TARGET_RATE);
            MemoryUtil.memFree(sb);

            System.out.println("[AudioLoader] WAV OK: " + new java.io.File(path).getName()
                    + " (" + (dstSampleCount / TARGET_RATE) + "s @ "
                    + channels + "ch " + srcRate + "Hz " + bitsPerSample + "bit -> 22050Hz mono)");
            return bufId;

        } catch (Exception e) {
            System.out.println("[AudioLoader] WAV FAIL: " + path + " -- " + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- OGG

    /**
     * Load an OGG Vorbis file into an OpenAL buffer using STBVorbis.
     * Converts to mono 16-bit 22050Hz, truncated to 90 seconds.
     * Returns 0 on failure.
     */
    public static int loadOgg(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                System.out.println("[AudioLoader] OGG not found: " + path);
                return 0;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer channelsBuf = stack.mallocInt(1);
                java.nio.IntBuffer rateBuf     = stack.mallocInt(1);

                ShortBuffer decoded = STBVorbis.stb_vorbis_decode_filename(
                        file.getAbsolutePath(), channelsBuf, rateBuf);
                if (decoded == null) {
                    System.out.println("[AudioLoader] OGG decode failed: " + path);
                    return 0;
                }

                int channels             = channelsBuf.get(0);
                int srcRate              = rateBuf.get(0);
                int framesPerChannel     = decoded.remaining() / channels;
                int dstSampleCount       = (int) Math.min((long) framesPerChannel * TARGET_RATE / srcRate, MAX_SAMPLES);
                ShortBuffer mono         = MemoryUtil.memAllocShort(dstSampleCount);

                for (int i = 0; i < dstSampleCount; i++) {
                    int srcIdx = (int) ((long) i * srcRate / TARGET_RATE);
                    if (srcIdx >= framesPerChannel) { mono.put(i, (short) 0); continue; }
                    long sum = 0;
                    for (int c = 0; c < channels; c++) sum += decoded.get(srcIdx * channels + c);
                    mono.put(i, (short) (sum / channels));
                }

                int bufId = alGenBuffers();
                alBufferData(bufId, AL_FORMAT_MONO16, mono, TARGET_RATE);
                MemoryUtil.memFree(mono);
                MemoryUtil.memFree(decoded);

                System.out.println("[AudioLoader] OGG OK: " + file.getName()
                        + " (" + (dstSampleCount / TARGET_RATE) + "s @ "
                        + channels + "ch " + srcRate + "Hz -> 22050Hz mono)");
                return bufId;
            }

        } catch (Exception e) {
            System.out.println("[AudioLoader] OGG FAIL: " + path + " -- " + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------- helpers

    /** Read a little-endian signed 16-bit int from byte array */
    private static int readShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** Read a little-endian signed 32-bit int from byte array */
    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
             | ((b[off + 1] & 0xFF) << 8)
             | ((b[off + 2] & 0xFF) << 16)
             | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * Read one PCM sample from raw bytes and return as a 16-bit signed short.
     * Supports 8, 16, 24, 32-bit integer PCM (little-endian).
     */
    private static short readSampleAs16(byte[] b, int off, int bytesPerSample) {
        switch (bytesPerSample) {
            case 1: // 8-bit unsigned -> 16-bit signed
                return (short) (((b[off] & 0xFF) - 128) << 8);
            case 2: // 16-bit signed little-endian
                return (short) ((b[off] & 0xFF) | (b[off + 1] << 8));
            case 3: // 24-bit signed little-endian -> top 16 bits
                int v24 = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | (b[off + 2] << 16);
                return (short) (v24 >> 8);
            case 4: // 32-bit signed little-endian -> top 16 bits
                int v32 = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                        | ((b[off + 2] & 0xFF) << 16) | (b[off + 3] << 24);
                return (short) (v32 >> 16);
            default:
                return 0;
        }
    }
}
