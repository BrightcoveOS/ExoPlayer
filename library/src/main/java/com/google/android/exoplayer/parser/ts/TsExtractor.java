/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.parser.ts;

import android.annotation.SuppressLint;
import android.media.MediaExtractor;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.upstream.NonBlockingInputStream;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.CodecSpecificDataUtil;
import com.google.android.exoplayer.util.MimeTypes;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Facilitates the extraction of data from the MPEG-2 TS container format.
 */
public final class TsExtractor {

  /**
   * An attempt to read from the input stream returned insufficient data.
   */
  public static final int RESULT_NEED_MORE_DATA = 1;
  /**
   * A media sample was read.
   */
  public static final int RESULT_READ_SAMPLE = 2;
  /**
   * The next thing to be read is a sample, but a {@link SampleHolder} was not supplied.
   */
  public static final int RESULT_NEED_SAMPLE_HOLDER = 4;

  private static final String TAG = "TsExtractor";

  private static final int TS_PACKET_SIZE = 188;
  private static final int TS_SYNC_BYTE = 0x47; // First byte of each TS packet.
  private static final int TS_PAT_PID = 0;

  private static final int TS_STREAM_TYPE_AAC = 0x0F;
  private static final int TS_STREAM_TYPE_H264 = 0x1B;
  private static final int TS_STREAM_TYPE_METADATA_PES = 0x15;

  private static final int DEFAULT_BUFFER_SEGMENT_SIZE = 64 * 1024;

  private final BitsArray tsPacketBuffer;
  private final SparseArray<PesPayloadReader> pesPayloadReaders; // Indexed by streamType
  private final SparseArray<TsPayloadReader> tsPayloadReaders; // Indexed by pid
  private final Queue<Sample> samplesPool;

  private boolean prepared;

  public TsExtractor() {
    tsPacketBuffer = new BitsArray();
    pesPayloadReaders = new SparseArray<PesPayloadReader>();
    tsPayloadReaders = new SparseArray<TsPayloadReader>();
    tsPayloadReaders.put(TS_PAT_PID, new PatReader());
    samplesPool = new LinkedList<Sample>();
  }

  /**
   * Gets the number of available tracks.
   * <p>
   * This method should only be called after the extractor has been prepared.
   *
   * @return The number of available tracks.
   */
  public int getTrackCount() {
    Assertions.checkState(prepared);
    return pesPayloadReaders.size();
  }

  /**
   * Gets the format of the specified track.
   * <p>
   * This method must only be called after the extractor has been prepared.
   *
   * @param track The track index.
   * @return The corresponding format.
   */
  public MediaFormat getFormat(int track) {
    Assertions.checkState(prepared);
    return pesPayloadReaders.valueAt(track).getMediaFormat();
  }

  /**
   * Resets the extractor's internal state.
   */
  public void reset() {
    prepared = false;
    tsPacketBuffer.reset();
    tsPayloadReaders.clear();
    tsPayloadReaders.put(TS_PAT_PID, new PatReader());
    // Clear each reader before discarding it, so as to recycle any queued Sample objects.
    for (int i = 0; i < pesPayloadReaders.size(); i++) {
      pesPayloadReaders.valueAt(i).clear();
    }
    pesPayloadReaders.clear();
  }

  /**
   * Attempts to prepare the extractor. The extractor is prepared once it has read sufficient data
   * to have established the available tracks and their corresponding media formats.
   * <p>
   * Calling this method is a no-op if the extractor is already prepared.
   *
   * @param inputStream The input stream from which data can be read.
   * @return True if the extractor was prepared. False if more data is required.
   */
  public boolean prepare(NonBlockingInputStream inputStream) {
    while (!prepared) {
      if (readTSPacket(inputStream) == -1) {
        return false;
      }
      prepared = checkPrepared();
    }
    return true;
  }

  private boolean checkPrepared() {
    int pesPayloadReaderCount = pesPayloadReaders.size();
    if (pesPayloadReaderCount == 0) {
      return false;
    }
    for (int i = 0; i < pesPayloadReaderCount; i++) {
      if (!pesPayloadReaders.valueAt(i).hasMediaFormat()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Consumes data from a {@link NonBlockingInputStream}.
   * <p>
   * The read terminates if the end of the input stream is reached, if insufficient data is
   * available to read a sample, or if a sample is read. The returned flags indicate
   * both the reason for termination and data that was parsed during the read.
   *
   * @param inputStream The input stream from which data should be read.
   * @param track The track from which to read.
   * @param out A {@link SampleHolder} into which the next sample should be read. If null then
   *     {@link #RESULT_NEED_SAMPLE_HOLDER} will be returned once a sample has been reached.
   * @return One or more of the {@code RESULT_*} flags defined in this class.
   */
  public int read(NonBlockingInputStream inputStream, int track, SampleHolder out) {
    Assertions.checkState(prepared);
    Queue<Sample> queue = pesPayloadReaders.valueAt(track).samplesQueue;

    // Keep reading if the buffer is empty.
    while (queue.isEmpty()) {
      if (readTSPacket(inputStream) == -1) {
        return RESULT_NEED_MORE_DATA;
      }
    }

    if (!queue.isEmpty() && out == null) {
      return RESULT_NEED_SAMPLE_HOLDER;
    }

    Sample sample = queue.remove();
    convert(sample, out);
    recycleSample(sample);
    return RESULT_READ_SAMPLE;
  }

  /**
   * Read a single TS packet.
   */
  private int readTSPacket(NonBlockingInputStream inputStream) {
    // Read entire single TS packet.
    if (inputStream.getAvailableByteCount() < TS_PACKET_SIZE) {
      return -1;
    }

    tsPacketBuffer.reset();

    int bytesRead = tsPacketBuffer.append(inputStream, TS_PACKET_SIZE);
    if (bytesRead != TS_PACKET_SIZE) {
      return -1;
    }

    // Parse TS header.
    // Check sync byte.
    int syncByte = tsPacketBuffer.readUnsignedByte();
    if (syncByte != TS_SYNC_BYTE) {
      return 0;
    }
    // Skip transportErrorIndicator.
    tsPacketBuffer.skipBits(1);
    int payloadUnitStartIndicator = tsPacketBuffer.readBits(1);
    // Skip transportPriority.
    tsPacketBuffer.skipBits(1);
    int pid = tsPacketBuffer.readBits(13);
    // Skip transport_scrambling_control.
    tsPacketBuffer.skipBits(2);
    int adaptationFieldExist = tsPacketBuffer.readBits(1);
    int payloadExist = tsPacketBuffer.readBits(1);
    // Skip continuityCounter.
    tsPacketBuffer.skipBits(4);

    // Read Adaptation Field.
    if (adaptationFieldExist == 1) {
      int afLength = tsPacketBuffer.readBits(8);
      tsPacketBuffer.skipBytes(afLength);
    }

    // Read Payload.
    if (payloadExist == 1) {
      TsPayloadReader payloadReader = tsPayloadReaders.get(pid);
      if (payloadReader == null) {
        return 0;
      }
      payloadReader.read(tsPacketBuffer, payloadUnitStartIndicator);
    }
    return 0;
  }

  private void convert(Sample in, SampleHolder out) {
    if (out.data == null || out.data.capacity() < in.size) {
      if (out.allowDataBufferReplacement) {
        out.data = ByteBuffer.allocate(in.size);
      } else {
        throw new IndexOutOfBoundsException("Buffer too small, and replacement not enabled");
      }
    }
    out.data.put(in.data, 0, in.size);
    out.size = in.size;
    out.flags = in.flags;
    out.timeUs = in.timeUs;
  }

  private Sample getSample() {
    if (samplesPool.isEmpty()) {
      return new Sample(DEFAULT_BUFFER_SEGMENT_SIZE);
    }
    return samplesPool.remove();
  }

  private void recycleSample(Sample sample) {
    sample.reset();
    samplesPool.add(sample);
  }

  /**
   * Parses payload data.
   */
  private abstract static class TsPayloadReader {
    public abstract void read(BitsArray tsBuffer, int payloadUnitStartIndicator);
  }

  /**
   * Parses Program Association Table data.
   */
  private class PatReader extends TsPayloadReader {

    @Override
    public void read(BitsArray tsBuffer, int payloadUnitStartIndicator) {
      // Skip pointer.
      if (payloadUnitStartIndicator == 1) {
        int pointerField = tsBuffer.readBits(8);
        tsBuffer.skipBytes(pointerField);
      }

      // Skip PAT header.
      tsBuffer.skipBits(64); // 8+1+1+2+12+16+2+5+1+8+8

      // Only read the first program and take it.

      // Skip program_number.
      tsBuffer.skipBits(16 + 3);
      int pid = tsBuffer.readBits(13);

      // Pick the first program.
      if (tsPayloadReaders.get(pid) == null) {
        tsPayloadReaders.put(pid, new PmtReader());
      }

      // Skip other programs if exist.
      // Skip CRC_32.
    }

  }

  /**
   * Parses Program Map Table.
   */
  private class PmtReader extends TsPayloadReader {

    @Override
    public void read(BitsArray tsBuffer, int payloadUnitStartIndicator) {
      // Skip pointer.
      if (payloadUnitStartIndicator == 1) {
        int pointerField = tsBuffer.readBits(8);
        tsBuffer.skipBytes(pointerField);
      }

      // Skip table_id, section_syntax_indicator, etc.
      tsBuffer.skipBits(12); // 8+1+1+2
      int sectionLength = tsBuffer.readBits(12);
      // Skip the rest of the PMT header.
      tsBuffer.skipBits(60); // 16+2+5+1+8+8+3+13+4
      int programInfoLength = tsBuffer.readBits(12);

      // Read descriptors.
      readDescriptors(tsBuffer, programInfoLength);

      int entriesSize = sectionLength - 9 /* size of the rest of the fields before descriptors */
          - programInfoLength - 4 /* CRC size */;
      while (entriesSize > 0) {
        int streamType = tsBuffer.readBits(8);
        tsBuffer.skipBits(3);
        int elementaryPid = tsBuffer.readBits(13);
        tsBuffer.skipBits(4);
        int esInfoLength = tsBuffer.readBits(12);

        readDescriptors(tsBuffer, esInfoLength);
        entriesSize -= esInfoLength + 5;

        if (pesPayloadReaders.get(streamType) != null) {
          continue;
        }

        PesPayloadReader pesPayloadReader = null;
        switch (streamType) {
          case TS_STREAM_TYPE_AAC:
            pesPayloadReader = new AdtsReader();
            break;
          case TS_STREAM_TYPE_H264:
            pesPayloadReader = new H264Reader();
            break;
          case TS_STREAM_TYPE_METADATA_PES:
            pesPayloadReader = new ID3Reader();
            break;
        }

        if (pesPayloadReader != null) {
          pesPayloadReaders.put(streamType, pesPayloadReader);
          tsPayloadReaders.put(elementaryPid, new PesReader(pesPayloadReader));
        }
      }

      // Skip CRC_32.
    }

    private void readDescriptors(BitsArray tsBuffer, int descriptorsSize) {
      while (descriptorsSize > 0) {
        // Skip tag.
        tsBuffer.skipBits(8);
        int descriptorsLength = tsBuffer.readBits(8);
        if (descriptorsLength > 0) {
          // Skip entire descriptor data.
          tsBuffer.skipBytes(descriptorsLength);
        }
        descriptorsSize -= descriptorsSize + 2;
      }
    }

  }

  /**
   * Parses PES packet data and extracts samples.
   */
  private class PesReader extends TsPayloadReader {

    // Reusable buffer for incomplete PES data.
    private final BitsArray pesBuffer;
    // Parses PES payload and extracts individual samples.
    private final PesPayloadReader pesPayloadReader;

    public PesReader(PesPayloadReader pesPayloadReader) {
      this.pesPayloadReader = pesPayloadReader;
      pesBuffer = new BitsArray();
    }

    @Override
    public void read(BitsArray tsBuffer, int payloadUnitStartIndicator) {
      if (payloadUnitStartIndicator == 1 && !pesBuffer.isEmpty()) {
        readPES();
      }
      pesBuffer.append(tsBuffer, tsBuffer.bytesLeft());
    }

    /**
     * Parses completed PES data.
     */
    private void readPES() {
      int packetStartCodePrefix = pesBuffer.readBits(24);
      if (packetStartCodePrefix != 0x000001) {
        // Error.
      }
      // TODO: Read and use stream_id.
      // Skip stream_id.
      pesBuffer.skipBits(8);
      int pesPacketLength = pesBuffer.readBits(16);

      // Skip some fields/flags.
      // TODO: might need to use data_alignment_indicator.
      pesBuffer.skipBits(8); // 2+2+1+1+1+1
      int ptsFlag = pesBuffer.readBits(1);
      // Skip DTS flag.
      pesBuffer.skipBits(1);
      // Skip some fields/flags.
      pesBuffer.skipBits(6); // 1+1+1+1+1+1

      int pesHeaderDataLength = pesBuffer.readBits(8);
      if (pesHeaderDataLength == 0) {
        pesHeaderDataLength = pesBuffer.bytesLeft();
      }

      long timeUs = 0;

      if (ptsFlag == 1) {
        // Skip prefix.
        pesBuffer.skipBits(4);
        long pts = pesBuffer.readBitsLong(3) << 30;
        pesBuffer.skipBits(1);
        pts |= pesBuffer.readBitsLong(15) << 15;
        pesBuffer.skipBits(1);
        pts |= pesBuffer.readBitsLong(15);
        pesBuffer.skipBits(1);

        timeUs = pts * 1000000 / 90000;

        // Skip the rest of the header.
        pesBuffer.skipBytes(pesHeaderDataLength - 5);
      } else {
        // Skip the rest of the header.
        pesBuffer.skipBytes(pesHeaderDataLength);
      }

      int payloadSize;
      if (pesPacketLength == 0) {
        // If pesPacketLength is not specified read all available data.
        payloadSize = pesBuffer.bytesLeft();
      } else {
        payloadSize = pesPacketLength - pesHeaderDataLength - 3;
      }

      pesPayloadReader.read(pesBuffer, payloadSize, timeUs);

      pesBuffer.reset();
    }

  }

  /**
   * Extracts individual samples from continuous byte stream.
   */
  private abstract class PesPayloadReader {

    public final Queue<Sample> samplesQueue;

    private MediaFormat mediaFormat;

    protected PesPayloadReader() {
      this.samplesQueue = new LinkedList<Sample>();
    }

    public boolean hasMediaFormat() {
      return mediaFormat != null;
    }

    public MediaFormat getMediaFormat() {
      return mediaFormat;
    }

    protected void setMediaFormat(MediaFormat mediaFormat) {
      this.mediaFormat = mediaFormat;
    }

    public abstract void read(BitsArray pesBuffer, int pesPayloadSize, long pesTimeUs);

    public void clear() {
      while (!samplesQueue.isEmpty()) {
        recycleSample(samplesQueue.remove());
      }
    }

    /**
     * Creates a new Sample and adds it to the queue.
     *
     * @param buffer The buffer to read sample data.
     * @param sampleSize The size of the sample data.
     * @param sampleTimeUs The sample time stamp.
     */
    protected void addSample(BitsArray buffer, int sampleSize, long sampleTimeUs, int flags) {
      Sample sample = getSample();
      addToSample(sample, buffer, sampleSize);
      sample.flags = flags;
      sample.timeUs = sampleTimeUs;
      samplesQueue.add(sample);
    }

    protected void addToSample(Sample sample, BitsArray buffer, int size) {
      if (sample.data.length - sample.size < size) {
        sample.expand(size - sample.data.length + sample.size);
      }
      buffer.readBytes(sample.data, sample.size, size);
      sample.size += size;
    }

  }

  /**
   * Parses a continuous H264 byte stream and extracts individual frames.
   */
  private class H264Reader extends PesPayloadReader {

    // IDR picture.
    private static final int NAL_UNIT_TYPE_IDR = 5;
    // Access unit delimiter.
    private static final int NAL_UNIT_TYPE_AUD = 9;

    // Used to store uncompleted sample data.
    private Sample currentSample;

    public H264Reader() {
      // TODO: Parse the format from the stream.
      setMediaFormat(MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, MediaFormat.NO_VALUE,
              1920, 1080, null));
    }

    @Override
    public void read(BitsArray pesBuffer, int pesPayloadSize, long pesTimeUs) {
      // Read leftover frame data from previous PES packet.
      pesPayloadSize -= readOneH264Frame(pesBuffer, true);

      if (pesBuffer.bytesLeft() <= 0 || pesPayloadSize <= 0) {
        return;
      }

      // Single PES packet should contain only one new H.264 frame.
      if (currentSample != null) {
        samplesQueue.add(currentSample);
      }
      currentSample = getSample();
      pesPayloadSize -= readOneH264Frame(pesBuffer, false);
      currentSample.timeUs = pesTimeUs;

      if (pesPayloadSize > 0) {
        Log.e(TAG, "PES packet contains more frame data than expected");
      }
    }

    @SuppressLint("InlinedApi")
    private int readOneH264Frame(BitsArray pesBuffer, boolean remainderOnly) {
      int offset = remainderOnly ? 0 : 3;
      int audStart = pesBuffer.findNextNalUnit(NAL_UNIT_TYPE_AUD, offset);
      int idrStart = pesBuffer.findNextNalUnit(NAL_UNIT_TYPE_IDR, offset);
      if (audStart > 0) {
        if (currentSample != null) {
          addToSample(currentSample, pesBuffer, audStart);
          if (idrStart < audStart) {
            currentSample.flags = MediaExtractor.SAMPLE_FLAG_SYNC;
          }
        } else {
          pesBuffer.skipBytes(audStart);
        }
        return audStart;
      }
      return 0;
    }

    @Override
    public void clear() {
      super.clear();
      if (currentSample != null) {
        recycleSample(currentSample);
        currentSample = null;
      }
    }
  }

  /**
   * Parses a continuous ADTS byte stream and extracts individual frames.
   */
  private class AdtsReader extends PesPayloadReader {

    private final BitsArray adtsBuffer;
    private long timeUs;

    public AdtsReader() {
      adtsBuffer = new BitsArray();
    }

    @Override
    public void read(BitsArray pesBuffer, int pesPayloadSize, long pesTimeUs) {
      boolean needToProcessLeftOvers = !adtsBuffer.isEmpty();
      adtsBuffer.append(pesBuffer, pesPayloadSize);
      // If there are leftovers from previous PES packet, process it with last calculated timeUs.
      if (needToProcessLeftOvers && !readOneAacFrame(timeUs)) {
        return;
      }
      int frameIndex = 0;
      do {
        long frameDuration = 0;
        // If frameIndex > 0, audioMediaFormat should be already parsed.
        // If frameIndex == 0, timeUs = pesTimeUs anyway.
        if (hasMediaFormat()) {
          frameDuration = 1000000L * 1024L / getMediaFormat().sampleRate;
        }
        timeUs = pesTimeUs + frameIndex * frameDuration;
        frameIndex++;
      } while(readOneAacFrame(timeUs));
    }

    @SuppressLint("InlinedApi")
    private boolean readOneAacFrame(long timeUs) {
      if (adtsBuffer.isEmpty()) {
        return false;
      }

      int offsetToSyncWord = adtsBuffer.findNextAdtsSyncWord();
      adtsBuffer.skipBytes(offsetToSyncWord);

      int adtsStartOffset = adtsBuffer.getByteOffset();

      if (adtsBuffer.bytesLeft() < 7) {
        adtsBuffer.setByteOffset(adtsStartOffset);
        adtsBuffer.clearReadData();
        return false;
      }

      adtsBuffer.skipBits(15);
      int hasCRC = adtsBuffer.readBits(1);

      if (!hasMediaFormat()) {
        int audioObjectType = adtsBuffer.readBits(2) + 1;
        int sampleRateIndex = adtsBuffer.readBits(4);
        adtsBuffer.skipBits(1);
        int channelConfig = adtsBuffer.readBits(3);

        byte[] audioSpecificConfig = CodecSpecificDataUtil.buildAudioSpecificConfig(
            audioObjectType, sampleRateIndex, channelConfig);
        Pair<Integer, Integer> audioParams = CodecSpecificDataUtil.parseAudioSpecificConfig(
            audioSpecificConfig);

        MediaFormat mediaFormat = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC,
            MediaFormat.NO_VALUE, audioParams.second, audioParams.first,
            Collections.singletonList(audioSpecificConfig));
        setMediaFormat(mediaFormat);
      } else {
        adtsBuffer.skipBits(10);
      }

      adtsBuffer.skipBits(4);
      int frameSize = adtsBuffer.readBits(13);
      adtsBuffer.skipBits(13);

      // Decrement frame size by ADTS header size and CRC.
      if (hasCRC == 0) {
        // Skip CRC.
        adtsBuffer.skipBytes(2);
        frameSize -= 9;
      } else {
        frameSize -= 7;
      }

      if (frameSize > adtsBuffer.bytesLeft()) {
        adtsBuffer.setByteOffset(adtsStartOffset);
        adtsBuffer.clearReadData();
        return false;
      }

      addSample(adtsBuffer, frameSize, timeUs, MediaExtractor.SAMPLE_FLAG_SYNC);
      return true;
    }

    @Override
    public void clear() {
      super.clear();
      adtsBuffer.reset();
    }

  }

  private class ID3Reader extends PesPayloadReader {

      private final BitsArray id3Buffer;

      public ID3Reader() {
        id3Buffer = new BitsArray();
        setMediaFormat(MediaFormat.createMetadataFormat());
      }

      @Override
      public void read(BitsArray pesBuffer, int pesPayloadSize, long pesTimeUs) {
          id3Buffer.append(pesBuffer, pesPayloadSize);
          byte[] id3Bytes = new byte[3];
          id3Buffer.readBytes(id3Bytes, 0, 3);
          String id3String = new String(id3Bytes);

          if (!id3String.equals("ID3")) {
              Log.e(TAG, "Error = not an ID3 tag. Header did not start with 'ID3");
          }

          id3Buffer.skipBytes(2);
          int id3Flags = id3Buffer.readBits(8);
          boolean id3ExtendedHeader = (id3Flags & 0x40) != 0;
          int size = id3Buffer.readBits(32);
          if (size == 0 || size > pesPayloadSize) {
              Log.e(TAG, "Error - ID3 tag size is incorrect.");
          }
          if (id3ExtendedHeader) {
              id3Buffer.skipBytes(10);
          }

          byte[] frameId = new byte[4];
          id3Buffer.readBytes(frameId, id3Buffer.getByteOffset(), 4);
          String frameIdStr = new String(frameId);
          Log.v(TAG, "ID3 frameIdStr: " + frameIdStr);

//              // skip the 2 version bytes for the ID3 tag
//              int id3TagOffset = offset + 5;
//              int id3Flags = packet.get(id3TagOffset);
//              boolean id3ExtendedHeader = (id3Flags & 0x40) !=0;
//
//              id3TagOffset++;
//
//              int size = getSynchSafeInteger(packet, id3TagOffset);
//              if (size == 0 || size > packet.length()) {
//                  Log.e(TAG, "Error - ID3 tag size is incorrect.");
//              }
//
//              id3TagOffset+=4;
//
//              if (id3ExtendedHeader) {
//                  id3TagOffset += 10;
//              }
//
//              List<ID3Data> id3Data = new ArrayList<ID3Data>();
//
//              int sizeOffset = id3TagOffset + size;
//              while (id3TagOffset < sizeOffset) {
//                  String frameId = new String(packet.array(), id3TagOffset, 4);
//                  id3TagOffset += 4;
//                  int frameSize = getSynchSafeInteger(packet, id3TagOffset);
//                  id3TagOffset += 6; // 4 for the size, skipped the 2 for the flags
//                  String frameData = new String(packet.array(), id3TagOffset, frameSize);
//                  id3Data.add(new ID3Data(frameId, frameData));
//
//                  id3TagOffset += frameSize;
//              }



          id3Buffer.reset();
      }

      @Override
      public void clear() {
          super.clear();
          id3Buffer.reset();
      }
  }

  /**
   * Simplified version of SampleHolder for internal buffering.
   */
  private static class Sample {

    public byte[] data;
    public int flags;
    public int size;
    public long timeUs;

    public Sample(int length) {
      data = new byte[length];
    }

    public void expand(int length) {
      byte[] newBuffer = new byte[data.length + length];
      System.arraycopy(data, 0, newBuffer, 0, size);
      data = newBuffer;
    }

    public void reset() {
      flags = 0;
      size = 0;
      timeUs = 0;
    }

  }

}
