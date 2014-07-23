package org.apache.lucene.codecs.memory;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.PagedBytes;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.fst.BytesRefFSTEnum;
import org.apache.lucene.util.fst.BytesRefFSTEnum.InputOutput;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FST.Arc;
import org.apache.lucene.util.fst.FST.BytesReader;
import org.apache.lucene.util.fst.PositiveIntOutputs;
import org.apache.lucene.util.fst.Util;
import org.apache.lucene.util.packed.BlockPackedReader;
import org.apache.lucene.util.packed.MonotonicBlockPackedReader;
import org.apache.lucene.util.packed.PackedInts;

/**
 * Reader for {@link MemoryDocValuesFormat}
 */
class MemoryDocValuesProducer extends DocValuesProducer {
  // metadata maps (just file pointers and minimal stuff)
  private final Map<Integer,NumericEntry> numerics = new HashMap<>();
  private final Map<Integer,BinaryEntry> binaries = new HashMap<>();
  private final Map<Integer,FSTEntry> fsts = new HashMap<>();
  private final Map<Integer,SortedSetEntry> sortedSets = new HashMap<>();
  private final Map<Integer,SortedNumericEntry> sortedNumerics = new HashMap<>();
  private final IndexInput data;
  
  // ram instances we have already loaded
  private final Map<Integer,NumericDocValues> numericInstances = 
      new HashMap<>();
  private final Map<Integer,BytesAndAddresses> pagedBytesInstances =
      new HashMap<>();
  private final Map<Integer,FST<Long>> fstInstances =
      new HashMap<>();
  private final Map<Integer,Bits> docsWithFieldInstances = new HashMap<>();
  private final Map<Integer,MonotonicBlockPackedReader> addresses = new HashMap<>();
  
  private final int maxDoc;
  private final AtomicLong ramBytesUsed;
  private final int version;
  
  static final byte NUMBER = 0;
  static final byte BYTES = 1;
  static final byte FST = 2;
  static final byte SORTED_SET = 4;
  static final byte SORTED_SET_SINGLETON = 5;
  static final byte SORTED_NUMERIC = 6;
  static final byte SORTED_NUMERIC_SINGLETON = 7;

  static final int BLOCK_SIZE = 4096;
  
  static final byte DELTA_COMPRESSED = 0;
  static final byte TABLE_COMPRESSED = 1;
  static final byte BLOCK_COMPRESSED = 2;
  static final byte GCD_COMPRESSED = 3;
  
  static final int VERSION_START = 3;
  static final int VERSION_CURRENT = VERSION_START;
    
  MemoryDocValuesProducer(SegmentReadState state, String dataCodec, String dataExtension, String metaCodec, String metaExtension) throws IOException {
    maxDoc = state.segmentInfo.getDocCount();
    String metaName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);
    // read in the entries from the metadata file.
    ChecksumIndexInput in = state.directory.openChecksumInput(metaName, state.context);
    boolean success = false;
    try {
      version = CodecUtil.checkHeader(in, metaCodec, 
                                      VERSION_START,
                                      VERSION_CURRENT);
      readFields(in, state.fieldInfos);
      CodecUtil.checkFooter(in);
      ramBytesUsed = new AtomicLong(RamUsageEstimator.shallowSizeOfInstance(getClass()));
      success = true;
    } finally {
      if (success) {
        IOUtils.close(in);
      } else {
        IOUtils.closeWhileHandlingException(in);
      }
    }

    String dataName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
    this.data = state.directory.openInput(dataName, state.context);
    success = false;
    try {
      final int version2 = CodecUtil.checkHeader(data, dataCodec, 
                                                 VERSION_START,
                                                 VERSION_CURRENT);
      if (version != version2) {
        throw new CorruptIndexException("Format versions mismatch");
      }
      
      // NOTE: data file is too costly to verify checksum against all the bytes on open,
      // but for now we at least verify proper structure of the checksum footer: which looks
      // for FOOTER_MAGIC + algorithmID. This is cheap and can detect some forms of corruption
      // such as file truncation.
      CodecUtil.retrieveChecksum(data);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this.data);
      }
    }
  }
  
  private NumericEntry readNumericEntry(IndexInput meta) throws IOException {
    NumericEntry entry = new NumericEntry();
    entry.offset = meta.readLong();
    entry.missingOffset = meta.readLong();
    if (entry.missingOffset != -1) {
      entry.missingBytes = meta.readLong();
    } else {
      entry.missingBytes = 0;
    }
    entry.format = meta.readByte();
    switch(entry.format) {
      case DELTA_COMPRESSED:
      case TABLE_COMPRESSED:
      case BLOCK_COMPRESSED:
      case GCD_COMPRESSED:
           break;
      default:
           throw new CorruptIndexException("Unknown format: " + entry.format + ", input=" + meta);
    }
    entry.packedIntsVersion = meta.readVInt();
    entry.count = meta.readLong();
    return entry;
  }
  
  private BinaryEntry readBinaryEntry(IndexInput meta) throws IOException {
    BinaryEntry entry = new BinaryEntry();
    entry.offset = meta.readLong();
    entry.numBytes = meta.readLong();
    entry.missingOffset = meta.readLong();
    if (entry.missingOffset != -1) {
      entry.missingBytes = meta.readLong();
    } else {
      entry.missingBytes = 0;
    }
    entry.minLength = meta.readVInt();
    entry.maxLength = meta.readVInt();
    if (entry.minLength != entry.maxLength) {
      entry.packedIntsVersion = meta.readVInt();
      entry.blockSize = meta.readVInt();
    }
    return entry;
  }
  
  private FSTEntry readFSTEntry(IndexInput meta) throws IOException {
    FSTEntry entry = new FSTEntry();
    entry.offset = meta.readLong();
    entry.numOrds = meta.readVLong();
    return entry;
  }
  
  private void readFields(IndexInput meta, FieldInfos infos) throws IOException {
    int fieldNumber = meta.readVInt();
    while (fieldNumber != -1) {
      int fieldType = meta.readByte();
      if (fieldType == NUMBER) {
        numerics.put(fieldNumber, readNumericEntry(meta));
      } else if (fieldType == BYTES) {
        binaries.put(fieldNumber, readBinaryEntry(meta));
      } else if (fieldType == FST) {
        fsts.put(fieldNumber,readFSTEntry(meta));
      } else if (fieldType == SORTED_SET) {
        SortedSetEntry entry = new SortedSetEntry();
        entry.singleton = false;
        sortedSets.put(fieldNumber, entry);
      } else if (fieldType == SORTED_SET_SINGLETON) {
        SortedSetEntry entry = new SortedSetEntry();
        entry.singleton = true;
        sortedSets.put(fieldNumber, entry);
      } else if (fieldType == SORTED_NUMERIC) {
        SortedNumericEntry entry = new SortedNumericEntry();
        entry.singleton = false;
        entry.packedIntsVersion = meta.readVInt();
        entry.blockSize = meta.readVInt();
        entry.addressOffset = meta.readLong();
        entry.valueCount = meta.readLong();
        sortedNumerics.put(fieldNumber, entry);
      } else if (fieldType == SORTED_NUMERIC_SINGLETON) {
        SortedNumericEntry entry = new SortedNumericEntry();
        entry.singleton = true;
        sortedNumerics.put(fieldNumber, entry);
      } else {
        throw new CorruptIndexException("invalid entry type: " + fieldType + ", input=" + meta);
      }
      fieldNumber = meta.readVInt();
    }
  }

  @Override
  public synchronized NumericDocValues getNumeric(FieldInfo field) throws IOException {
    NumericDocValues instance = numericInstances.get(field.number);
    if (instance == null) {
      instance = loadNumeric(field);
      numericInstances.put(field.number, instance);
    }
    return instance;
  }
  
  @Override
  public long ramBytesUsed() {
    return ramBytesUsed.get();
  }
  
  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(data);
  }
  
  private NumericDocValues loadNumeric(FieldInfo field) throws IOException {
    NumericEntry entry = numerics.get(field.number);
    data.seek(entry.offset + entry.missingBytes);
    switch (entry.format) {
      case TABLE_COMPRESSED:
        int size = data.readVInt();
        if (size > 256) {
          throw new CorruptIndexException("TABLE_COMPRESSED cannot have more than 256 distinct values, input=" + data);
        }
        final long decode[] = new long[size];
        for (int i = 0; i < decode.length; i++) {
          decode[i] = data.readLong();
        }
        final int formatID = data.readVInt();
        final int bitsPerValue = data.readVInt();
        final PackedInts.Reader ordsReader = PackedInts.getReaderNoHeader(data, PackedInts.Format.byId(formatID), entry.packedIntsVersion, (int)entry.count, bitsPerValue);
        ramBytesUsed.addAndGet(RamUsageEstimator.sizeOf(decode) + ordsReader.ramBytesUsed());
        return new NumericDocValues() {
          @Override
          public long get(int docID) {
            return decode[(int)ordsReader.get(docID)];
          }
        };
      case DELTA_COMPRESSED:
        final long minDelta = data.readLong();
        final int formatIDDelta = data.readVInt();
        final int bitsPerValueDelta = data.readVInt();
        final PackedInts.Reader deltaReader = PackedInts.getReaderNoHeader(data, PackedInts.Format.byId(formatIDDelta), entry.packedIntsVersion, (int)entry.count, bitsPerValueDelta);
        ramBytesUsed.addAndGet(deltaReader.ramBytesUsed());
        return new NumericDocValues() {
          @Override
          public long get(int docID) {
            return minDelta + deltaReader.get(docID);
          }
        };
      case BLOCK_COMPRESSED:
        final int blockSize = data.readVInt();
        final BlockPackedReader reader = new BlockPackedReader(data, entry.packedIntsVersion, blockSize, entry.count, false);
        ramBytesUsed.addAndGet(reader.ramBytesUsed());
        return reader;
      case GCD_COMPRESSED:
        final long min = data.readLong();
        final long mult = data.readLong();
        final int formatIDGCD = data.readVInt();
        final int bitsPerValueGCD = data.readVInt();
        final PackedInts.Reader quotientReader = PackedInts.getReaderNoHeader(data, PackedInts.Format.byId(formatIDGCD), entry.packedIntsVersion, (int)entry.count, bitsPerValueGCD);
        ramBytesUsed.addAndGet(quotientReader.ramBytesUsed());
        return new NumericDocValues() {
          @Override
          public long get(int docID) {
            return min + mult * quotientReader.get(docID);
          }
        };
      default:
        throw new AssertionError();
    }
  }

  @Override
  public BinaryDocValues getBinary(FieldInfo field) throws IOException {
    BinaryEntry entry = binaries.get(field.number);

    BytesAndAddresses instance;
    synchronized (this) {
      instance = pagedBytesInstances.get(field.number);
      if (instance == null) {
        instance = loadBinary(field);
        pagedBytesInstances.put(field.number, instance);
      }
    }
    final PagedBytes.Reader bytesReader = instance.reader;
    final MonotonicBlockPackedReader addresses = instance.addresses;

    if (addresses == null) {
      assert entry.minLength == entry.maxLength;
      final int fixedLength = entry.minLength;
      return new BinaryDocValues() {
        final BytesRef term = new BytesRef();

        @Override
        public BytesRef get(int docID) {
          bytesReader.fillSlice(term, fixedLength * (long)docID, fixedLength);
          return term;
        }
      };
    } else {
      return new BinaryDocValues() {
        final BytesRef term = new BytesRef();

        @Override
        public BytesRef get(int docID) {
          long startAddress = docID == 0 ? 0 : addresses.get(docID-1);
          long endAddress = addresses.get(docID);
          bytesReader.fillSlice(term, startAddress, (int) (endAddress - startAddress));
          return term;
        }
      };
    }
  }
  
  private BytesAndAddresses loadBinary(FieldInfo field) throws IOException {
    BytesAndAddresses bytesAndAddresses = new BytesAndAddresses();
    BinaryEntry entry = binaries.get(field.number);
    data.seek(entry.offset);
    PagedBytes bytes = new PagedBytes(16);
    bytes.copy(data, entry.numBytes);
    bytesAndAddresses.reader = bytes.freeze(true);
    ramBytesUsed.addAndGet(bytesAndAddresses.reader.ramBytesUsed());
    if (entry.minLength != entry.maxLength) {
      data.seek(data.getFilePointer() + entry.missingBytes);
      bytesAndAddresses.addresses = MonotonicBlockPackedReader.of(data, entry.packedIntsVersion, entry.blockSize, maxDoc, false);
      ramBytesUsed.addAndGet(bytesAndAddresses.addresses.ramBytesUsed());
    }
    return bytesAndAddresses;
  }
  
  @Override
  public SortedDocValues getSorted(FieldInfo field) throws IOException {
    final FSTEntry entry = fsts.get(field.number);
    if (entry.numOrds == 0) {
      return DocValues.emptySorted();
    }
    FST<Long> instance;
    synchronized(this) {
      instance = fstInstances.get(field.number);
      if (instance == null) {
        data.seek(entry.offset);
        instance = new FST<>(data, PositiveIntOutputs.getSingleton());
        ramBytesUsed.addAndGet(instance.ramBytesUsed());
        fstInstances.put(field.number, instance);
      }
    }
    final NumericDocValues docToOrd = getNumeric(field);
    final FST<Long> fst = instance;
    
    // per-thread resources
    final BytesReader in = fst.getBytesReader();
    final Arc<Long> firstArc = new Arc<>();
    final Arc<Long> scratchArc = new Arc<>();
    final IntsRef scratchInts = new IntsRef();
    final BytesRefFSTEnum<Long> fstEnum = new BytesRefFSTEnum<>(fst);
    
    return new SortedDocValues() {
      final BytesRef term = new BytesRef();

      @Override
      public int getOrd(int docID) {
        return (int) docToOrd.get(docID);
      }

      @Override
      public BytesRef lookupOrd(int ord) {
        try {
          in.setPosition(0);
          fst.getFirstArc(firstArc);
          IntsRef output = Util.getByOutput(fst, ord, in, firstArc, scratchArc, scratchInts);
          Util.toBytesRef(output, term);
          return term;
        } catch (IOException bogus) {
          throw new RuntimeException(bogus);
        }
      }

      @Override
      public int lookupTerm(BytesRef key) {
        try {
          InputOutput<Long> o = fstEnum.seekCeil(key);
          if (o == null) {
            return -getValueCount()-1;
          } else if (o.input.equals(key)) {
            return o.output.intValue();
          } else {
            return (int) -o.output-1;
          }
        } catch (IOException bogus) {
          throw new RuntimeException(bogus);
        }
      }

      @Override
      public int getValueCount() {
        return (int)entry.numOrds;
      }

      @Override
      public TermsEnum termsEnum() {
        return new FSTTermsEnum(fst);
      }
    };
  }
  
  @Override
  public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
    SortedNumericEntry entry = sortedNumerics.get(field.number);
    if (entry.singleton) {
      NumericDocValues values = getNumeric(field);
      NumericEntry ne = numerics.get(field.number);
      Bits docsWithField = getMissingBits(field.number, ne.missingOffset, ne.missingBytes);
      return DocValues.singleton(values, docsWithField);
    } else {
      final NumericDocValues values = getNumeric(field);
      final MonotonicBlockPackedReader addr;
      synchronized (this) {
        MonotonicBlockPackedReader res = addresses.get(field.number);
        if (res == null) {
          data.seek(entry.addressOffset);
          res = MonotonicBlockPackedReader.of(data, entry.packedIntsVersion, entry.blockSize, entry.valueCount, false);
          addresses.put(field.number, res);
        }
        addr = res;
      }
      if (values instanceof LongValues) {
        // probably not the greatest codec choice for this situation, but we support it
        final LongValues longValues = (LongValues) values;
        return new SortedNumericDocValues() {
          long startOffset;
          long endOffset;
          
          @Override
          public void setDocument(int doc) {
            startOffset = (int) addr.get(doc);
            endOffset = (int) addr.get(doc+1L);
          }

          @Override
          public long valueAt(int index) {
            return longValues.get(startOffset + index);
          }

          @Override
          public int count() {
            return (int) (endOffset - startOffset);
          }
        };
      } else {
        return new SortedNumericDocValues() {
          int startOffset;
          int endOffset;
        
          @Override
          public void setDocument(int doc) {
            startOffset = (int) addr.get(doc);
            endOffset = (int) addr.get(doc+1);
          }

          @Override
          public long valueAt(int index) {
            return values.get(startOffset + index);
          }

          @Override
          public int count() {
            return (endOffset - startOffset);
          }
        };
      }
    }
  }
  
  @Override
  public SortedSetDocValues getSortedSet(FieldInfo field) throws IOException {
    SortedSetEntry sortedSetEntry = sortedSets.get(field.number);
    if (sortedSetEntry.singleton) {
      return DocValues.singleton(getSorted(field));
    }
    
    final FSTEntry entry = fsts.get(field.number);
    if (entry.numOrds == 0) {
      return DocValues.emptySortedSet(); // empty FST!
    }
    FST<Long> instance;
    synchronized(this) {
      instance = fstInstances.get(field.number);
      if (instance == null) {
        data.seek(entry.offset);
        instance = new FST<>(data, PositiveIntOutputs.getSingleton());
        ramBytesUsed.addAndGet(instance.ramBytesUsed());
        fstInstances.put(field.number, instance);
      }
    }
    final BinaryDocValues docToOrds = getBinary(field);
    final FST<Long> fst = instance;
    
    // per-thread resources
    final BytesReader in = fst.getBytesReader();
    final Arc<Long> firstArc = new Arc<>();
    final Arc<Long> scratchArc = new Arc<>();
    final IntsRef scratchInts = new IntsRef();
    final BytesRefFSTEnum<Long> fstEnum = new BytesRefFSTEnum<>(fst);
    final ByteArrayDataInput input = new ByteArrayDataInput();
    return new SortedSetDocValues() {
      final BytesRef term = new BytesRef();
      BytesRef ref;
      long currentOrd;

      @Override
      public long nextOrd() {
        if (input.eof()) {
          return NO_MORE_ORDS;
        } else {
          currentOrd += input.readVLong();
          return currentOrd;
        }
      }
      
      @Override
      public void setDocument(int docID) {
        ref = docToOrds.get(docID);
        input.reset(ref.bytes, ref.offset, ref.length);
        currentOrd = 0;
      }

      @Override
      public BytesRef lookupOrd(long ord) {
        try {
          in.setPosition(0);
          fst.getFirstArc(firstArc);
          IntsRef output = Util.getByOutput(fst, ord, in, firstArc, scratchArc, scratchInts);
          Util.toBytesRef(output, term);
          return term;
        } catch (IOException bogus) {
          throw new RuntimeException(bogus);
        }
      }

      @Override
      public long lookupTerm(BytesRef key) {
        try {
          InputOutput<Long> o = fstEnum.seekCeil(key);
          if (o == null) {
            return -getValueCount()-1;
          } else if (o.input.equals(key)) {
            return o.output.intValue();
          } else {
            return -o.output-1;
          }
        } catch (IOException bogus) {
          throw new RuntimeException(bogus);
        }
      }

      @Override
      public long getValueCount() {
        return entry.numOrds;
      }

      @Override
      public TermsEnum termsEnum() {
        return new FSTTermsEnum(fst);
      }
    };
  }
  
  private Bits getMissingBits(int fieldNumber, final long offset, final long length) throws IOException {
    if (offset == -1) {
      return new Bits.MatchAllBits(maxDoc);
    } else {
      Bits instance;
      synchronized(this) {
        instance = docsWithFieldInstances.get(fieldNumber);
        if (instance == null) {
          IndexInput data = this.data.clone();
          data.seek(offset);
          assert length % 8 == 0;
          long bits[] = new long[(int) length >> 3];
          for (int i = 0; i < bits.length; i++) {
            bits[i] = data.readLong();
          }
          instance = new FixedBitSet(bits, maxDoc);
          docsWithFieldInstances.put(fieldNumber, instance);
        }
      }
      return instance;
    }
  }
  
  @Override
  public Bits getDocsWithField(FieldInfo field) throws IOException {
    switch(field.getDocValuesType()) {
      case SORTED_SET:
        return DocValues.docsWithValue(getSortedSet(field), maxDoc);
      case SORTED_NUMERIC:
        return DocValues.docsWithValue(getSortedNumeric(field), maxDoc);
      case SORTED:
        return DocValues.docsWithValue(getSorted(field), maxDoc);
      case BINARY:
        BinaryEntry be = binaries.get(field.number);
        return getMissingBits(field.number, be.missingOffset, be.missingBytes);
      case NUMERIC:
        NumericEntry ne = numerics.get(field.number);
        return getMissingBits(field.number, ne.missingOffset, ne.missingBytes);
      default: 
        throw new AssertionError();
    }
  }

  @Override
  public void close() throws IOException {
    data.close();
  }
  
  static class NumericEntry {
    long offset;
    long count;
    long missingOffset;
    long missingBytes;
    byte format;
    int packedIntsVersion;
  }
  
  static class BinaryEntry {
    long offset;
    long missingOffset;
    long missingBytes;
    long numBytes;
    int minLength;
    int maxLength;
    int packedIntsVersion;
    int blockSize;
  }
  
  static class FSTEntry {
    long offset;
    long numOrds;
  }
  
  static class SortedSetEntry {
    boolean singleton;
  }
  
  static class SortedNumericEntry {
    boolean singleton;
    long addressOffset;
    int packedIntsVersion;
    int blockSize;
    long valueCount;
  }

  static class BytesAndAddresses {
    PagedBytes.Reader reader;
    MonotonicBlockPackedReader addresses;
  }

  // exposes FSTEnum directly as a TermsEnum: avoids binary-search next()
  static class FSTTermsEnum extends TermsEnum {
    final BytesRefFSTEnum<Long> in;
    
    // this is all for the complicated seek(ord)...
    // maybe we should add a FSTEnum that supports this operation?
    final FST<Long> fst;
    final FST.BytesReader bytesReader;
    final Arc<Long> firstArc = new Arc<>();
    final Arc<Long> scratchArc = new Arc<>();
    final IntsRef scratchInts = new IntsRef();
    final BytesRef scratchBytes = new BytesRef();
    
    FSTTermsEnum(FST<Long> fst) {
      this.fst = fst;
      in = new BytesRefFSTEnum<>(fst);
      bytesReader = fst.getBytesReader();
    }

    @Override
    public BytesRef next() throws IOException {
      InputOutput<Long> io = in.next();
      if (io == null) {
        return null;
      } else {
        return io.input;
      }
    }

    @Override
    public Comparator<BytesRef> getComparator() {
      return BytesRef.getUTF8SortedAsUnicodeComparator();
    }

    @Override
    public SeekStatus seekCeil(BytesRef text) throws IOException {
      if (in.seekCeil(text) == null) {
        return SeekStatus.END;
      } else if (term().equals(text)) {
        // TODO: add SeekStatus to FSTEnum like in https://issues.apache.org/jira/browse/LUCENE-3729
        // to remove this comparision?
        return SeekStatus.FOUND;
      } else {
        return SeekStatus.NOT_FOUND;
      }
    }

    @Override
    public boolean seekExact(BytesRef text) throws IOException {
      if (in.seekExact(text) == null) {
        return false;
      } else {
        return true;
      }
    }

    @Override
    public void seekExact(long ord) throws IOException {
      // TODO: would be better to make this simpler and faster.
      // but we dont want to introduce a bug that corrupts our enum state!
      bytesReader.setPosition(0);
      fst.getFirstArc(firstArc);
      IntsRef output = Util.getByOutput(fst, ord, bytesReader, firstArc, scratchArc, scratchInts);
      scratchBytes.bytes = new byte[output.length];
      scratchBytes.offset = 0;
      scratchBytes.length = 0;
      Util.toBytesRef(output, scratchBytes);
      // TODO: we could do this lazily, better to try to push into FSTEnum though?
      in.seekExact(scratchBytes);
    }

    @Override
    public BytesRef term() throws IOException {
      return in.current().input;
    }

    @Override
    public long ord() throws IOException {
      return in.current().output;
    }

    @Override
    public int docFreq() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long totalTermFreq() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public DocsEnum docs(Bits liveDocs, DocsEnum reuse, int flags) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public DocsAndPositionsEnum docsAndPositions(Bits liveDocs, DocsAndPositionsEnum reuse, int flags) throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
