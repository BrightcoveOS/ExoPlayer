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
package com.google.android.exoplayer.extractor.webm;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.drm.DrmInitData;
import com.google.android.exoplayer.extractor.ChunkIndex;
import com.google.android.exoplayer.extractor.Extractor;
import com.google.android.exoplayer.extractor.ExtractorInput;
import com.google.android.exoplayer.extractor.ExtractorOutput;
import com.google.android.exoplayer.extractor.PositionHolder;
import com.google.android.exoplayer.extractor.TrackOutput;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.LongArray;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.NalUnitUtil;
import com.google.android.exoplayer.util.ParsableByteArray;

import android.util.Pair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An extractor to facilitate data retrieval from the WebM container format.
 * <p>
 * WebM is a subset of the EBML elements defined for Matroska. More information about EBML and
 * Matroska is available <a href="http://www.matroska.org/technical/specs/index.html">here</a>.
 * More info about WebM is <a href="http://www.webmproject.org/code/specs/container/">here</a>.
 * RFC on encrypted WebM can be found
 * <a href="http://wiki.webmproject.org/encryption/webm-encryption-rfc">here</a>.
 */
public final class WebmExtractor implements Extractor {

  private static final int BLOCK_STATE_START = 0;
  private static final int BLOCK_STATE_HEADER = 1;
  private static final int BLOCK_STATE_DATA = 2;

  private static final int CUES_STATE_NOT_BUILT = 0;
  private static final int CUES_STATE_BUILDING = 1;
  private static final int CUES_STATE_BUILT = 2;

  private static final String DOC_TYPE_WEBM = "webm";
  private static final String DOC_TYPE_MATROSKA = "matroska";
  private static final String CODEC_ID_VP8 = "V_VP8";
  private static final String CODEC_ID_VP9 = "V_VP9";
  private static final String CODEC_ID_H264 = "V_MPEG4/ISO/AVC";
  private static final String CODEC_ID_VORBIS = "A_VORBIS";
  private static final String CODEC_ID_OPUS = "A_OPUS";
  private static final String CODEC_ID_AAC = "A_AAC";
  private static final String CODEC_ID_AC3 = "A_AC3";
  private static final int VORBIS_MAX_INPUT_SIZE = 8192;
  private static final int OPUS_MAX_INPUT_SIZE = 5760;
  private static final int ENCRYPTION_IV_SIZE = 8;
  private static final int TRACK_TYPE_AUDIO = 2;
  private static final int TRACK_TYPE_VIDEO = 1;
  private static final int UNKNOWN = -1;

  private static final int ID_EBML = 0x1A45DFA3;
  private static final int ID_EBML_READ_VERSION = 0x42F7;
  private static final int ID_DOC_TYPE = 0x4282;
  private static final int ID_DOC_TYPE_READ_VERSION = 0x4285;
  private static final int ID_SEGMENT = 0x18538067;
  private static final int ID_SEEK_HEAD = 0x114D9B74;
  private static final int ID_SEEK = 0x4DBB;
  private static final int ID_SEEK_ID = 0x53AB;
  private static final int ID_SEEK_POSITION = 0x53AC;
  private static final int ID_INFO = 0x1549A966;
  private static final int ID_TIMECODE_SCALE = 0x2AD7B1;
  private static final int ID_DURATION = 0x4489;
  private static final int ID_CLUSTER = 0x1F43B675;
  private static final int ID_TIME_CODE = 0xE7;
  private static final int ID_SIMPLE_BLOCK = 0xA3;
  private static final int ID_BLOCK_GROUP = 0xA0;
  private static final int ID_BLOCK = 0xA1;
  private static final int ID_REFERENCE_BLOCK = 0xFB;
  private static final int ID_TRACKS = 0x1654AE6B;
  private static final int ID_TRACK_ENTRY = 0xAE;
  private static final int ID_TRACK_NUMBER = 0xD7;
  private static final int ID_TRACK_TYPE = 0x83;
  private static final int ID_DEFAULT_DURATION = 0x23E383;
  private static final int ID_CODEC_ID = 0x86;
  private static final int ID_CODEC_PRIVATE = 0x63A2;
  private static final int ID_CODEC_DELAY = 0x56AA;
  private static final int ID_SEEK_PRE_ROLL = 0x56BB;
  private static final int ID_VIDEO = 0xE0;
  private static final int ID_PIXEL_WIDTH = 0xB0;
  private static final int ID_PIXEL_HEIGHT = 0xBA;
  private static final int ID_AUDIO = 0xE1;
  private static final int ID_CHANNELS = 0x9F;
  private static final int ID_SAMPLING_FREQUENCY = 0xB5;
  private static final int ID_CONTENT_ENCODINGS = 0x6D80;
  private static final int ID_CONTENT_ENCODING = 0x6240;
  private static final int ID_CONTENT_ENCODING_ORDER = 0x5031;
  private static final int ID_CONTENT_ENCODING_SCOPE = 0x5032;
  private static final int ID_CONTENT_ENCODING_TYPE = 0x5033;
  private static final int ID_CONTENT_ENCRYPTION = 0x5035;
  private static final int ID_CONTENT_ENCRYPTION_ALGORITHM = 0x47E1;
  private static final int ID_CONTENT_ENCRYPTION_KEY_ID = 0x47E2;
  private static final int ID_CONTENT_ENCRYPTION_AES_SETTINGS = 0x47E7;
  private static final int ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE = 0x47E8;
  private static final int ID_CUES = 0x1C53BB6B;
  private static final int ID_CUE_POINT = 0xBB;
  private static final int ID_CUE_TIME = 0xB3;
  private static final int ID_CUE_TRACK_POSITIONS = 0xB7;
  private static final int ID_CUE_CLUSTER_POSITION = 0xF1;

  private static final int LACING_NONE = 0;
  private static final int LACING_XIPH = 1;
  private static final int LACING_FIXED_SIZE = 2;
  private static final int LACING_EBML = 3;

  private final EbmlReader reader;
  private final VarintReader varintReader;

  // Temporary arrays.
  private final ParsableByteArray nalStartCode;
  private final ParsableByteArray nalLength;
  private final ParsableByteArray scratch;
  private final ParsableByteArray vorbisNumPageSamples;
  private final ParsableByteArray seekEntryIdBytes;

  private long segmentContentPosition = UNKNOWN;
  private long segmentContentSize = UNKNOWN;
  private long timecodeScale = 1000000L;
  private long durationUs = C.UNKNOWN_TIME_US;

  private TrackFormat trackFormat;  // Used to store the last seen track.
  private TrackFormat audioTrackFormat;
  private TrackFormat videoTrackFormat;

  private boolean sentDrmInitData;

  // Master seek entry related elements.
  private int seekEntryId;
  private long seekEntryPosition;

  // Cue related elements.
  private boolean seekForCues;
  private long cuesContentPosition = UNKNOWN;
  private long seekPositionAfterBuildingCues = UNKNOWN;
  private int cuesState = CUES_STATE_NOT_BUILT;
  private long clusterTimecodeUs = UNKNOWN;
  private LongArray cueTimesUs;
  private LongArray cueClusterPositions;
  private boolean seenClusterPositionForCurrentCuePoint;

  // Block reading state.
  private int blockState;
  private long blockTimeUs;
  private int blockLacingSampleIndex;
  private int blockLacingSampleCount;
  private int[] blockLacingSampleSizes;
  private int blockTrackNumber;
  private int blockTrackNumberLength;
  private int blockFlags;
  private byte[] blockEncryptionKeyId;

  // Sample reading state.
  private int sampleBytesRead;
  private boolean sampleEncryptionDataRead;
  private int sampleCurrentNalBytesRemaining;
  private int sampleBytesWritten;
  private boolean sampleRead;
  private boolean sampleSeenReferenceBlock;

  // Extractor outputs.
  private ExtractorOutput extractorOutput;

  public WebmExtractor() {
    this(new DefaultEbmlReader());
  }

  /* package */ WebmExtractor(EbmlReader reader) {
    this.reader = reader;
    this.reader.init(new InnerEbmlReaderOutput());
    varintReader = new VarintReader();
    scratch = new ParsableByteArray(4);
    vorbisNumPageSamples = new ParsableByteArray(ByteBuffer.allocate(4).putInt(-1).array());
    seekEntryIdBytes = new ParsableByteArray(4);
    nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
    nalLength = new ParsableByteArray(4);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
  }

  @Override
  public void seek() {
    clusterTimecodeUs = UNKNOWN;
    blockState = BLOCK_STATE_START;
    reader.reset();
    varintReader.reset();
    sampleCurrentNalBytesRemaining = 0;
    sampleBytesRead = 0;
    sampleBytesWritten = 0;
    sampleEncryptionDataRead = false;
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException,
      InterruptedException {
    sampleRead = false;
    boolean continueReading = true;
    while (continueReading && !sampleRead) {
      continueReading = reader.read(input);
      if (continueReading && maybeSeekForCues(seekPosition, input.getPosition())) {
        return Extractor.RESULT_SEEK;
      }
    }
    return continueReading ? Extractor.RESULT_CONTINUE : Extractor.RESULT_END_OF_INPUT;
  }

  /* package */ int getElementType(int id) {
    switch (id) {
      case ID_EBML:
      case ID_SEGMENT:
      case ID_SEEK_HEAD:
      case ID_SEEK:
      case ID_INFO:
      case ID_CLUSTER:
      case ID_TRACKS:
      case ID_TRACK_ENTRY:
      case ID_AUDIO:
      case ID_VIDEO:
      case ID_CONTENT_ENCODINGS:
      case ID_CONTENT_ENCODING:
      case ID_CONTENT_ENCRYPTION:
      case ID_CONTENT_ENCRYPTION_AES_SETTINGS:
      case ID_CUES:
      case ID_CUE_POINT:
      case ID_CUE_TRACK_POSITIONS:
      case ID_BLOCK_GROUP:
        return EbmlReader.TYPE_MASTER;
      case ID_EBML_READ_VERSION:
      case ID_DOC_TYPE_READ_VERSION:
      case ID_SEEK_POSITION:
      case ID_TIMECODE_SCALE:
      case ID_TIME_CODE:
      case ID_PIXEL_WIDTH:
      case ID_PIXEL_HEIGHT:
      case ID_TRACK_NUMBER:
      case ID_TRACK_TYPE:
      case ID_DEFAULT_DURATION:
      case ID_CODEC_DELAY:
      case ID_SEEK_PRE_ROLL:
      case ID_CHANNELS:
      case ID_CONTENT_ENCODING_ORDER:
      case ID_CONTENT_ENCODING_SCOPE:
      case ID_CONTENT_ENCODING_TYPE:
      case ID_CONTENT_ENCRYPTION_ALGORITHM:
      case ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE:
      case ID_CUE_TIME:
      case ID_CUE_CLUSTER_POSITION:
      case ID_REFERENCE_BLOCK:
        return EbmlReader.TYPE_UNSIGNED_INT;
      case ID_DOC_TYPE:
      case ID_CODEC_ID:
        return EbmlReader.TYPE_STRING;
      case ID_SEEK_ID:
      case ID_CONTENT_ENCRYPTION_KEY_ID:
      case ID_SIMPLE_BLOCK:
      case ID_BLOCK:
      case ID_CODEC_PRIVATE:
        return EbmlReader.TYPE_BINARY;
      case ID_DURATION:
      case ID_SAMPLING_FREQUENCY:
        return EbmlReader.TYPE_FLOAT;
      default:
        return EbmlReader.TYPE_UNKNOWN;
    }
  }

  /* package */ void startMasterElement(int id, long contentPosition, long contentSize)
      throws ParserException {
    switch (id) {
      case ID_SEGMENT:
        if (segmentContentPosition != UNKNOWN) {
          throw new ParserException("Multiple Segment elements not supported");
        }
        segmentContentPosition = contentPosition;
        segmentContentSize = contentSize;
        return;
      case ID_SEEK:
        seekEntryId = UNKNOWN;
        seekEntryPosition = UNKNOWN;
        return;
      case ID_CUES:
        cueTimesUs = new LongArray();
        cueClusterPositions = new LongArray();
        return;
      case ID_CUE_POINT:
        seenClusterPositionForCurrentCuePoint = false;
        return;
      case ID_CLUSTER:
        // If we encounter a Cluster before building Cues, then we should try to build cues first
        // before parsing the Cluster.
        if (cuesState == CUES_STATE_NOT_BUILT && cuesContentPosition != UNKNOWN) {
          seekForCues = true;
        }
        return;
      case ID_BLOCK_GROUP:
        sampleSeenReferenceBlock = false;
        return;
      case ID_CONTENT_ENCODING:
        // TODO: check and fail if more than one content encoding is present.
        return;
      case ID_CONTENT_ENCRYPTION:
        trackFormat.hasContentEncryption = true;
        return;
      case ID_TRACK_ENTRY:
        trackFormat = new TrackFormat();
        return;
      default:
        return;
    }
  }

  /* package */ void endMasterElement(int id) throws ParserException {
    switch (id) {
      case ID_SEEK:
        if (seekEntryId == UNKNOWN || seekEntryPosition == UNKNOWN) {
          throw new ParserException("Mandatory element SeekID or SeekPosition not found");
        }
        if (seekEntryId == ID_CUES) {
          cuesContentPosition = seekEntryPosition;
        }
        return;
      case ID_CUES:
        if (cuesState != CUES_STATE_BUILT) {
          extractorOutput.seekMap(buildCues());
          cuesState = CUES_STATE_BUILT;
        } else {
          // We have already built the cues. Ignore.
        }
        return;
      case ID_BLOCK_GROUP:
        if (blockState != BLOCK_STATE_DATA) {
          // We've skipped this block (due to incompatible track number).
          return;
        }
        // If the ReferenceBlock element was not found for this sample, then it is a keyframe.
        if (!sampleSeenReferenceBlock) {
          blockFlags |= C.SAMPLE_FLAG_SYNC;
        }
        outputSampleMetadata(
            (audioTrackFormat != null && blockTrackNumber == audioTrackFormat.number)
                ? audioTrackFormat.trackOutput : videoTrackFormat.trackOutput, blockTimeUs);
        blockState = BLOCK_STATE_START;
        return;
      case ID_CONTENT_ENCODING:
        if (!trackFormat.hasContentEncryption) {
          // We found a ContentEncoding other than Encryption.
          throw new ParserException("Found an unsupported ContentEncoding");
        }
        if (trackFormat.encryptionKeyId == null) {
          throw new ParserException("Encrypted Track found but ContentEncKeyID was not found");
        }
        if (!sentDrmInitData) {
          extractorOutput.drmInitData(
              new DrmInitData.Universal(MimeTypes.VIDEO_WEBM, trackFormat.encryptionKeyId));
          sentDrmInitData = true;
        }
        return;
      case ID_TRACK_ENTRY:
        if (trackFormat.number == UNKNOWN || trackFormat.type == UNKNOWN) {
          throw new ParserException("Mandatory element TrackNumber or TrackType not found");
        }
        if ((trackFormat.type == TRACK_TYPE_AUDIO && audioTrackFormat != null)
            || (trackFormat.type == TRACK_TYPE_VIDEO && videoTrackFormat != null)) {
          // There is more than 1 audio/video track. Ignore everything but the first.
          trackFormat = null;
          return;
        }
        if (trackFormat.type == TRACK_TYPE_AUDIO && isCodecSupported(trackFormat.codecId)) {
          audioTrackFormat = trackFormat;
          audioTrackFormat.trackOutput = extractorOutput.track(audioTrackFormat.number);
          audioTrackFormat.trackOutput.format(audioTrackFormat.getMediaFormat(durationUs));
        } else if (trackFormat.type == TRACK_TYPE_VIDEO && isCodecSupported(trackFormat.codecId)) {
          videoTrackFormat = trackFormat;
          videoTrackFormat.trackOutput = extractorOutput.track(videoTrackFormat.number);
          videoTrackFormat.trackOutput.format(videoTrackFormat.getMediaFormat(durationUs));
        } else {
          // Unsupported track type. Do nothing.
        }
        trackFormat = null;
        return;
      case ID_TRACKS:
        if (videoTrackFormat == null && audioTrackFormat == null) {
          throw new ParserException("No valid tracks were found");
        }
        extractorOutput.endTracks();
        return;
      default:
        return;
    }
  }

  /* package */ void integerElement(int id, long value) throws ParserException {
    switch (id) {
      case ID_EBML_READ_VERSION:
        // Validate that EBMLReadVersion is supported. This extractor only supports v1.
        if (value != 1) {
          throw new ParserException("EBMLReadVersion " + value + " not supported");
        }
        return;
      case ID_DOC_TYPE_READ_VERSION:
        // Validate that DocTypeReadVersion is supported. This extractor only supports up to v2.
        if (value < 1 || value > 2) {
          throw new ParserException("DocTypeReadVersion " + value + " not supported");
        }
        return;
      case ID_SEEK_POSITION:
        // Seek Position is the relative offset beginning from the Segment. So to get absolute
        // offset from the beginning of the file, we need to add segmentContentPosition to it.
        seekEntryPosition = value + segmentContentPosition;
        return;
      case ID_TIMECODE_SCALE:
        timecodeScale = value;
        return;
      case ID_PIXEL_WIDTH:
        trackFormat.pixelWidth = (int) value;
        return;
      case ID_PIXEL_HEIGHT:
        trackFormat.pixelHeight = (int) value;
        return;
      case ID_TRACK_NUMBER:
        trackFormat.number = (int) value;
        return;
      case ID_TRACK_TYPE:
        trackFormat.type = (int) value;
        return;
      case ID_DEFAULT_DURATION:
        trackFormat.defaultSampleDurationNs = (int) value;
        break;
      case ID_CODEC_DELAY:
        trackFormat.codecDelayNs = value;
        return;
      case ID_SEEK_PRE_ROLL:
        trackFormat.seekPreRollNs = value;
        return;
      case ID_CHANNELS:
        trackFormat.channelCount = (int) value;
        return;
      case ID_REFERENCE_BLOCK:
        sampleSeenReferenceBlock = true;
        return;
      case ID_CONTENT_ENCODING_ORDER:
        // This extractor only supports one ContentEncoding element and hence the order has to be 0.
        if (value != 0) {
          throw new ParserException("ContentEncodingOrder " + value + " not supported");
        }
        return;
      case ID_CONTENT_ENCODING_SCOPE:
        // This extractor only supports the scope of all frames (since that's the only scope used
        // for Encryption).
        if (value != 1) {
          throw new ParserException("ContentEncodingScope " + value + " not supported");
        }
        return;
      case ID_CONTENT_ENCODING_TYPE:
        // This extractor only supports Encrypted ContentEncodingType.
        if (value != 1) {
          throw new ParserException("ContentEncodingType " + value + " not supported");
        }
        return;
      case ID_CONTENT_ENCRYPTION_ALGORITHM:
        // Only the value 5 (AES) is allowed according to the WebM specification.
        if (value != 5) {
          throw new ParserException("ContentEncAlgo " + value + " not supported");
        }
        return;
      case ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE:
        // Only the value 1 is allowed according to the WebM specification.
        if (value != 1) {
          throw new ParserException("AESSettingsCipherMode " + value + " not supported");
        }
        return;
      case ID_CUE_TIME:
        cueTimesUs.add(scaleTimecodeToUs(value));
        return;
      case ID_CUE_CLUSTER_POSITION:
        if (!seenClusterPositionForCurrentCuePoint) {
          // If there's more than one video/audio track, then there could be more than one
          // CueTrackPositions within a single CuePoint. In such a case, ignore all but the first
          // one (since the cluster position will be quite close for all the tracks).
          cueClusterPositions.add(value);
          seenClusterPositionForCurrentCuePoint = true;
        }
        return;
      case ID_TIME_CODE:
        clusterTimecodeUs = scaleTimecodeToUs(value);
        return;
      default:
        return;
    }
  }

  /* package */ void floatElement(int id, double value) {
    switch (id) {
      case ID_DURATION:
        durationUs = scaleTimecodeToUs((long) value);
        return;
      case ID_SAMPLING_FREQUENCY:
        trackFormat.sampleRate = (int) value;
        return;
      default:
        return;
    }
  }

  /* package */ void stringElement(int id, String value) throws ParserException {
    switch (id) {
      case ID_DOC_TYPE:
        // Validate that DocType is supported.
        if (!DOC_TYPE_WEBM.equals(value) && !DOC_TYPE_MATROSKA.equals(value)) {
          throw new ParserException("DocType " + value + " not supported");
        }
        return;
      case ID_CODEC_ID:
        trackFormat.codecId = value;
        return;
      default:
        return;
    }
  }

  /* package */ void binaryElement(int id, int contentSize, ExtractorInput input)
      throws IOException, InterruptedException {
    switch (id) {
      case ID_SEEK_ID:
        Arrays.fill(seekEntryIdBytes.data, (byte) 0);
        input.readFully(seekEntryIdBytes.data, 4 - contentSize, contentSize);
        seekEntryIdBytes.setPosition(0);
        seekEntryId = (int) seekEntryIdBytes.readUnsignedInt();
        return;
      case ID_CODEC_PRIVATE:
        trackFormat.codecPrivate = new byte[contentSize];
        input.readFully(trackFormat.codecPrivate, 0, contentSize);
        return;
      case ID_CONTENT_ENCRYPTION_KEY_ID:
        trackFormat.encryptionKeyId = new byte[contentSize];
        input.readFully(trackFormat.encryptionKeyId, 0, contentSize);
        return;
      case ID_SIMPLE_BLOCK:
      case ID_BLOCK:
        // Please refer to http://www.matroska.org/technical/specs/index.html#simpleblock_structure
        // and http://matroska.org/technical/specs/index.html#block_structure
        // for info about how data is organized in SimpleBlock and Block elements respectively. They
        // differ only in the way flags are specified.

        if (blockState == BLOCK_STATE_START) {
          blockTrackNumber = (int) varintReader.readUnsignedVarint(input, false, true);
          blockTrackNumberLength = varintReader.getLastLength();
          blockState = BLOCK_STATE_HEADER;
          scratch.reset();
        }

        // Ignore the block if the track number equals neither the audio track nor the video track.
        if ((audioTrackFormat != null && videoTrackFormat != null
                && audioTrackFormat.number != blockTrackNumber
                && videoTrackFormat.number != blockTrackNumber)
            || (audioTrackFormat != null && videoTrackFormat == null
                && audioTrackFormat.number != blockTrackNumber)
            || (audioTrackFormat == null && videoTrackFormat != null
                && videoTrackFormat.number != blockTrackNumber)) {
          input.skipFully(contentSize - blockTrackNumberLength);
          blockState = BLOCK_STATE_START;
          return;
        }

        TrackFormat sampleTrackFormat =
            (audioTrackFormat != null && blockTrackNumber == audioTrackFormat.number)
                ? audioTrackFormat : videoTrackFormat;
        TrackOutput trackOutput = sampleTrackFormat.trackOutput;

        if (blockState == BLOCK_STATE_HEADER) {
          // Read the relative timecode (2 bytes) and flags (1 byte).
          readScratch(input, 3);
          int lacing = (scratch.data[2] & 0x06) >> 1;
          if (lacing == LACING_NONE) {
            blockLacingSampleCount = 1;
            blockLacingSampleSizes = ensureArrayCapacity(blockLacingSampleSizes, 1);
            blockLacingSampleSizes[0] = contentSize - blockTrackNumberLength - 3;
          } else {
            if (id != ID_SIMPLE_BLOCK) {
              throw new ParserException("Lacing only supported in SimpleBlocks.");
            }

            // Read the sample count (1 byte).
            readScratch(input, 4);
            blockLacingSampleCount = (scratch.data[3] & 0xFF) + 1;
            blockLacingSampleSizes =
                ensureArrayCapacity(blockLacingSampleSizes, blockLacingSampleCount);
            if (lacing == LACING_FIXED_SIZE) {
              int blockLacingSampleSize =
                  (contentSize - blockTrackNumberLength - 4) / blockLacingSampleCount;
              Arrays.fill(blockLacingSampleSizes, 0, blockLacingSampleCount, blockLacingSampleSize);
            } else if (lacing == LACING_XIPH) {
              int totalSamplesSize = 0;
              int headerSize = 4;
              for (int sampleIndex = 0; sampleIndex < blockLacingSampleCount - 1; sampleIndex++) {
                blockLacingSampleSizes[sampleIndex] = 0;
                int byteValue;
                do {
                  readScratch(input, ++headerSize);
                  byteValue = scratch.data[headerSize - 1] & 0xFF;
                  blockLacingSampleSizes[sampleIndex] += byteValue;
                } while (byteValue == 0xFF);
                totalSamplesSize += blockLacingSampleSizes[sampleIndex];
              }
              blockLacingSampleSizes[blockLacingSampleCount - 1] =
                  contentSize - blockTrackNumberLength - headerSize - totalSamplesSize;
            } else if (lacing == LACING_EBML) {
              int totalSamplesSize = 0;
              int headerSize = 4;
              for (int sampleIndex = 0; sampleIndex < blockLacingSampleCount - 1; sampleIndex++) {
                blockLacingSampleSizes[sampleIndex] = 0;
                readScratch(input, ++headerSize);
                if (scratch.data[headerSize - 1] == 0) {
                  throw new ParserException("No valid varint length mask found");
                }
                long readValue = 0;
                for (int i = 0; i < 8; i++) {
                  int lengthMask = 1 << (7 - i);
                  if ((scratch.data[headerSize - 1] & lengthMask) != 0) {
                    int readPosition = headerSize - 1;
                    headerSize += i;
                    readScratch(input, headerSize);
                    readValue = (scratch.data[readPosition++] & 0xFF) & ~lengthMask;
                    while (readPosition < headerSize) {
                      readValue <<= 8;
                      readValue |= (scratch.data[readPosition++] & 0xFF);
                    }
                    // The first read value is the first size. Later values are signed offsets.
                    if (sampleIndex > 0) {
                      readValue -= (1L << 6 + i * 7) - 1;
                    }
                    break;
                  }
                }
                if (readValue < Integer.MIN_VALUE || readValue > Integer.MAX_VALUE) {
                  throw new ParserException("EBML lacing sample size out of range.");
                }
                int intReadValue = (int) readValue;
                blockLacingSampleSizes[sampleIndex] = sampleIndex == 0
                    ? intReadValue : blockLacingSampleSizes[sampleIndex - 1] + intReadValue;
                totalSamplesSize += blockLacingSampleSizes[sampleIndex];
              }
              blockLacingSampleSizes[blockLacingSampleCount - 1] =
                  contentSize - blockTrackNumberLength - headerSize - totalSamplesSize;
            } else {
              // Lacing is always in the range 0--3.
              throw new IllegalStateException("Unexpected lacing value: " + lacing);
            }
          }

          int timecode = (scratch.data[0] << 8) | (scratch.data[1] & 0xFF);
          blockTimeUs = clusterTimecodeUs + scaleTimecodeToUs(timecode);
          boolean isInvisible = (scratch.data[2] & 0x08) == 0x08;
          boolean isKeyframe = (id == ID_SIMPLE_BLOCK && (scratch.data[2] & 0x80) == 0x80);
          blockFlags = (isKeyframe ? C.SAMPLE_FLAG_SYNC : 0)
              | (isInvisible ? C.SAMPLE_FLAG_DECODE_ONLY : 0);
          blockEncryptionKeyId = sampleTrackFormat.encryptionKeyId;
          blockState = BLOCK_STATE_DATA;
          blockLacingSampleIndex = 0;
        }

        if (id == ID_SIMPLE_BLOCK) {
          // For SimpleBlock, we have metadata for each sample here.
          while (blockLacingSampleIndex < blockLacingSampleCount) {
            writeSampleData(input, trackOutput, sampleTrackFormat,
                blockLacingSampleSizes[blockLacingSampleIndex]);
            long sampleTimeUs = this.blockTimeUs
                + (blockLacingSampleIndex * sampleTrackFormat.defaultSampleDurationNs) / 1000;
            outputSampleMetadata(trackOutput, sampleTimeUs);
            blockLacingSampleIndex++;
          }
          blockState = BLOCK_STATE_START;
        } else {
          // For Block, we send the metadata at the end of the BlockGroup element since we'll know
          // if the sample is a keyframe or not only at that point.
          writeSampleData(input, trackOutput, sampleTrackFormat, blockLacingSampleSizes[0]);
        }

        return;
      default:
        throw new ParserException("Unexpected id: " + id);
    }
  }

  private void outputSampleMetadata(TrackOutput trackOutput, long timeUs) {
    trackOutput.sampleMetadata(timeUs, blockFlags, sampleBytesWritten, 0, blockEncryptionKeyId);
    sampleRead = true;
    sampleBytesRead = 0;
    sampleBytesWritten = 0;
    sampleEncryptionDataRead = false;
  }

  /**
   * Ensures {@link #scratch} contains at least {@code requiredLength} bytes of data, reading from
   * the extractor input if necessary.
   */
  private void readScratch(ExtractorInput input, int requiredLength)
      throws IOException, InterruptedException {
    if (scratch.limit() >= requiredLength) {
      return;
    }
    if (scratch.capacity() < requiredLength) {
      scratch.reset(Arrays.copyOf(scratch.data, Math.max(scratch.data.length * 2, requiredLength)),
          scratch.limit());
    }
    input.readFully(scratch.data, scratch.limit(), requiredLength - scratch.limit());
    scratch.setLimit(requiredLength);
  }

  private void writeSampleData(ExtractorInput input, TrackOutput output, TrackFormat format,
      int size) throws IOException, InterruptedException {
    // Read the sample's encryption signal byte and set the IV size if necessary.
    if (format.hasContentEncryption && !sampleEncryptionDataRead) {
      // Clear the encrypted flag.
      blockFlags &= ~C.SAMPLE_FLAG_ENCRYPTED;
      input.readFully(scratch.data, 0, 1);
      sampleBytesRead++;
      if ((scratch.data[0] & 0x80) == 0x80) {
        throw new ParserException("Extension bit is set in signal byte");
      }
      sampleEncryptionDataRead = true;

      // If the sample is encrypted, write the IV size instead of the signal byte, and set the flag.
      if ((scratch.data[0] & 0x01) == 0x01) {
        scratch.data[0] = (byte) ENCRYPTION_IV_SIZE;
        scratch.setPosition(0);
        output.sampleData(scratch, 1);
        sampleBytesWritten++;
        blockFlags |= C.SAMPLE_FLAG_ENCRYPTED;
      }
    }

    if (CODEC_ID_H264.equals(format.codecId)) {
      // TODO: Deduplicate with Mp4Extractor.

      // Zero the top three bytes of the array that we'll use to parse nal unit lengths, in case
      // they're only 1 or 2 bytes long.
      byte[] nalLengthData = nalLength.data;
      nalLengthData[0] = 0;
      nalLengthData[1] = 0;
      nalLengthData[2] = 0;
      int nalUnitLengthFieldLength = format.nalUnitLengthFieldLength;
      int nalUnitLengthFieldLengthDiff = 4 - format.nalUnitLengthFieldLength;
      // NAL units are length delimited, but the decoder requires start code delimited units.
      // Loop until we've written the sample to the track output, replacing length delimiters with
      // start codes as we encounter them.
      while (sampleBytesRead < size) {
        if (sampleCurrentNalBytesRemaining == 0) {
          // Read the NAL length so that we know where we find the next one.
          input.readFully(nalLengthData, nalUnitLengthFieldLengthDiff,
              nalUnitLengthFieldLength);
          nalLength.setPosition(0);
          sampleCurrentNalBytesRemaining = nalLength.readUnsignedIntToInt();
          // Write a start code for the current NAL unit.
          nalStartCode.setPosition(0);
          output.sampleData(nalStartCode, 4);
          sampleBytesRead += nalUnitLengthFieldLength;
          sampleBytesWritten += 4;
        } else {
          // Write the payload of the NAL unit.
          int writtenBytes = output.sampleData(input, sampleCurrentNalBytesRemaining);
          sampleCurrentNalBytesRemaining -= writtenBytes;
          sampleBytesRead += writtenBytes;
          sampleBytesWritten += writtenBytes;
        }
      }
    } else {
      while (sampleBytesRead < size) {
        int writtenBytes = output.sampleData(input, size - sampleBytesRead);
        sampleBytesRead += writtenBytes;
        sampleBytesWritten += writtenBytes;
      }
    }

    if (CODEC_ID_VORBIS.equals(format.codecId)) {
      // Vorbis decoder in android MediaCodec [1] expects the last 4 bytes of the sample to be the
      // number of samples in the current page. This definition holds good only for Ogg and
      // irrelevant for WebM. So we always set this to -1 (the decoder will ignore this value if we
      // set it to -1). The android platform media extractor [2] does the same.
      // [1] https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/codecs/vorbis/dec/SoftVorbis.cpp#314
      // [2] https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/NuMediaExtractor.cpp#474
      vorbisNumPageSamples.setPosition(0);
      output.sampleData(vorbisNumPageSamples, 4);
      sampleBytesWritten += 4;
    }
  }

  /**
   * Builds a {@link ChunkIndex} containing recently gathered Cues information.
   *
   * @return The built {@link ChunkIndex}.
   * @throws ParserException If the index could not be built.
   */
  private ChunkIndex buildCues() throws ParserException {
    if (segmentContentPosition == UNKNOWN) {
      throw new ParserException("Segment start/end offsets unknown");
    } else if (durationUs == C.UNKNOWN_TIME_US) {
      throw new ParserException("Duration unknown");
    } else if (cueTimesUs == null || cueClusterPositions == null
        || cueTimesUs.size() == 0 || cueTimesUs.size() != cueClusterPositions.size()) {
      throw new ParserException("Invalid/missing cue points");
    }
    int cuePointsSize = cueTimesUs.size();
    int[] sizes = new int[cuePointsSize];
    long[] offsets = new long[cuePointsSize];
    long[] durationsUs = new long[cuePointsSize];
    long[] timesUs = new long[cuePointsSize];
    for (int i = 0; i < cuePointsSize; i++) {
      timesUs[i] = cueTimesUs.get(i);
      offsets[i] = segmentContentPosition + cueClusterPositions.get(i);
    }
    for (int i = 0; i < cuePointsSize - 1; i++) {
      sizes[i] = (int) (offsets[i + 1] - offsets[i]);
      durationsUs[i] = timesUs[i + 1] - timesUs[i];
    }
    sizes[cuePointsSize - 1] =
        (int) (segmentContentPosition + segmentContentSize - offsets[cuePointsSize - 1]);
    durationsUs[cuePointsSize - 1] = durationUs - timesUs[cuePointsSize - 1];
    cueTimesUs = null;
    cueClusterPositions = null;
    return new ChunkIndex(sizes, offsets, durationsUs, timesUs);
  }

  /**
   * Updates the position of the holder to Cues element's position if the extractor configuration
   * permits use of master seek entry. After building Cues sets the holder's position back to where
   * it was before.
   *
   * @param seekPosition The holder whose position will be updated.
   * @param currentPosition Current position of the input.
   * @return true if the seek position was updated, false otherwise.
   */
  private boolean maybeSeekForCues(PositionHolder seekPosition, long currentPosition) {
    if (seekForCues) {
      seekPositionAfterBuildingCues = currentPosition;
      seekPosition.position = cuesContentPosition;
      cuesState = CUES_STATE_BUILDING;
      seekForCues = false;
      return true;
    }
    // After parsing Cues, Seek back to original position if available. We will not do this unless
    // we seeked to get to the Cues in the first place.
    if (cuesState == CUES_STATE_BUILT && seekPositionAfterBuildingCues != UNKNOWN) {
      seekPosition.position = seekPositionAfterBuildingCues;
      seekPositionAfterBuildingCues = UNKNOWN;
      return true;
    }
    return false;
  }

  private long scaleTimecodeToUs(long unscaledTimecode) {
    return TimeUnit.NANOSECONDS.toMicros(unscaledTimecode * timecodeScale);
  }

  private static boolean isCodecSupported(String codecId) {
    return CODEC_ID_VP8.equals(codecId)
        || CODEC_ID_VP9.equals(codecId)
        || CODEC_ID_H264.equals(codecId)
        || CODEC_ID_OPUS.equals(codecId)
        || CODEC_ID_VORBIS.equals(codecId)
        || CODEC_ID_AAC.equals(codecId)
        || CODEC_ID_AC3.equals(codecId);
  }

  /**
   * Returns an array that can store (at least) {@code length} elements, which will be either a new
   * array or {@code array} if it's not null and large enough.
   */
  private static int[] ensureArrayCapacity(int[] array, int length) {
    if (array == null) {
      return new int[length];
    } else if (array.length >= length) {
      return array;
    } else {
      // Double the size to avoid allocating constantly if the required length increases gradually.
      return new int[Math.max(array.length * 2, length)];
    }
  }

  /**
   * Passes events through to the outer {@link WebmExtractor}.
   */
  private final class InnerEbmlReaderOutput implements EbmlReaderOutput {

    @Override
    public int getElementType(int id) {
      return WebmExtractor.this.getElementType(id);
    }

    @Override
    public void startMasterElement(int id, long contentPosition, long contentSize)
        throws ParserException {
      WebmExtractor.this.startMasterElement(id, contentPosition, contentSize);
    }

    @Override
    public void endMasterElement(int id) throws ParserException {
      WebmExtractor.this.endMasterElement(id);
    }

    @Override
    public void integerElement(int id, long value) throws ParserException {
      WebmExtractor.this.integerElement(id, value);
    }

    @Override
    public void floatElement(int id, double value) {
      WebmExtractor.this.floatElement(id, value);
    }

    @Override
    public void stringElement(int id, String value) throws ParserException {
      WebmExtractor.this.stringElement(id, value);
    }

    @Override
    public void binaryElement(int id, int contentsSize, ExtractorInput input)
        throws IOException, InterruptedException {
      WebmExtractor.this.binaryElement(id, contentsSize, input);
    }

  }

  private static final class TrackFormat {

    // Common track elements.
    public String codecId;
    public int number = UNKNOWN;
    public int type = UNKNOWN;
    public int defaultSampleDurationNs = UNKNOWN;
    public boolean hasContentEncryption;
    public byte[] encryptionKeyId;
    public byte[] codecPrivate;

    // Video track related elements.
    public int pixelWidth = UNKNOWN;
    public int pixelHeight = UNKNOWN;
    public int nalUnitLengthFieldLength = UNKNOWN;

    // Audio track related elements.
    public int channelCount = UNKNOWN;
    public int sampleRate = UNKNOWN;
    public long codecDelayNs = UNKNOWN;
    public long seekPreRollNs = UNKNOWN;

    public TrackOutput trackOutput;

    /** Returns a {@link MediaFormat} built using the information in this instance. */
    public MediaFormat getMediaFormat(long durationUs) throws ParserException {
      String mimeType;
      List<byte[]> initializationData = null;
      int maxInputSize = UNKNOWN;
      switch (codecId) {
        case CODEC_ID_VP8:
          mimeType = MimeTypes.VIDEO_VP8;
          break;
        case CODEC_ID_VP9:
          mimeType = MimeTypes.VIDEO_VP9;
          break;
        case CODEC_ID_H264:
          mimeType = MimeTypes.VIDEO_H264;
          Pair<List<byte[]>, Integer> h264Data = parseH264CodecPrivate(
              new ParsableByteArray(codecPrivate));
          initializationData = h264Data.first;
          nalUnitLengthFieldLength = h264Data.second;
          break;
        case CODEC_ID_VORBIS:
          mimeType = MimeTypes.AUDIO_VORBIS;
          maxInputSize = VORBIS_MAX_INPUT_SIZE;
          initializationData = parseVorbisCodecPrivate(codecPrivate);
          break;
        case CODEC_ID_OPUS:
          mimeType = MimeTypes.AUDIO_OPUS;
          maxInputSize = OPUS_MAX_INPUT_SIZE;
          initializationData = new ArrayList<>(3);
          initializationData.add(codecPrivate);
          initializationData.add(ByteBuffer.allocate(Long.SIZE).putLong(codecDelayNs).array());
          initializationData.add(ByteBuffer.allocate(Long.SIZE).putLong(seekPreRollNs).array());
          break;
        case CODEC_ID_AAC:
          mimeType = MimeTypes.AUDIO_AAC;
          initializationData = Collections.singletonList(codecPrivate);
          break;
        case CODEC_ID_AC3:
          mimeType = MimeTypes.AUDIO_AC3;
          break;
        default:
          throw new ParserException("Unrecognized codec identifier.");
      }

      if (MimeTypes.isAudio(mimeType)) {
        return MediaFormat.createAudioFormat(mimeType, maxInputSize, durationUs, channelCount,
            sampleRate, initializationData);
      } else if (MimeTypes.isVideo(mimeType)) {
        return MediaFormat.createVideoFormat(mimeType, maxInputSize, durationUs, pixelWidth,
            pixelHeight, initializationData);
      } else {
        throw new ParserException("Unexpected MIME type.");
      }
    }

    /**
     * Builds initialization data for a {@link MediaFormat} from H.264 codec private data.
     *
     * @return The initialization data for the {@link MediaFormat}.
     * @throws ParserException If the initialization data could not be built.
     */
    private static Pair<List<byte[]>, Integer> parseH264CodecPrivate(ParsableByteArray buffer)
        throws ParserException {
      try {
        // TODO: Deduplicate with AtomParsers.parseAvcCFromParent.
        buffer.setPosition(4);
        int nalUnitLengthFieldLength = (buffer.readUnsignedByte() & 0x03) + 1;
        Assertions.checkState(nalUnitLengthFieldLength != 3);
        List<byte[]> initializationData = new ArrayList<>();
        int numSequenceParameterSets = buffer.readUnsignedByte() & 0x1F;
        for (int i = 0; i < numSequenceParameterSets; i++) {
          initializationData.add(NalUnitUtil.parseChildNalUnit(buffer));
        }
        int numPictureParameterSets = buffer.readUnsignedByte();
        for (int j = 0; j < numPictureParameterSets; j++) {
          initializationData.add(NalUnitUtil.parseChildNalUnit(buffer));
        }
        return Pair.create(initializationData, nalUnitLengthFieldLength);
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new ParserException("Error parsing vorbis codec private");
      }
    }

    /**
     * Builds initialization data for a {@link MediaFormat} from Vorbis codec private data.
     *
     * @return The initialization data for the {@link MediaFormat}.
     * @throws ParserException If the initialization data could not be built.
     */
    private static List<byte[]> parseVorbisCodecPrivate(byte[] codecPrivate)
        throws ParserException {
      try {
        if (codecPrivate[0] != 0x02) {
          throw new ParserException("Error parsing vorbis codec private");
        }
        int offset = 1;
        int vorbisInfoLength = 0;
        while (codecPrivate[offset] == (byte) 0xFF) {
          vorbisInfoLength += 0xFF;
          offset++;
        }
        vorbisInfoLength += codecPrivate[offset++];

        int vorbisSkipLength = 0;
        while (codecPrivate[offset] == (byte) 0xFF) {
          vorbisSkipLength += 0xFF;
          offset++;
        }
        vorbisSkipLength += codecPrivate[offset++];

        if (codecPrivate[offset] != 0x01) {
          throw new ParserException("Error parsing vorbis codec private");
        }
        byte[] vorbisInfo = new byte[vorbisInfoLength];
        System.arraycopy(codecPrivate, offset, vorbisInfo, 0, vorbisInfoLength);
        offset += vorbisInfoLength;
        if (codecPrivate[offset] != 0x03) {
          throw new ParserException("Error parsing vorbis codec private");
        }
        offset += vorbisSkipLength;
        if (codecPrivate[offset] != 0x05) {
          throw new ParserException("Error parsing vorbis codec private");
        }
        byte[] vorbisBooks = new byte[codecPrivate.length - offset];
        System.arraycopy(codecPrivate, offset, vorbisBooks, 0, codecPrivate.length - offset);
        List<byte[]> initializationData = new ArrayList<>(2);
        initializationData.add(vorbisInfo);
        initializationData.add(vorbisBooks);
        return initializationData;
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new ParserException("Error parsing vorbis codec private");
      }
    }

  }

}
