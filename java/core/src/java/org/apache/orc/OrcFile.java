/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.orc.impl.HadoopShims;
import org.apache.orc.impl.HadoopShimsFactory;
import org.apache.orc.impl.MemoryManagerImpl;
import org.apache.orc.impl.OrcTail;
import org.apache.orc.impl.ReaderImpl;
import org.apache.orc.impl.WriterImpl;
import org.apache.orc.impl.WriterInternal;
import org.apache.orc.impl.writer.WriterImplV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains factory methods to read or write ORC files.
 */
public class OrcFile {
  private static final Logger LOG = LoggerFactory.getLogger(OrcFile.class);
  public static final String MAGIC = "ORC";

  /**
   * Create a version number for the ORC file format, so that we can add
   * non-forward compatible changes in the future. To make it easier for users
   * to understand the version numbers, we use the Hive release number that
   * first wrote that version of ORC files.
   *
   * Thus, if you add new encodings or other non-forward compatible changes
   * to ORC files, which prevent the old reader from reading the new format,
   * you should change these variable to reflect the next Hive release number.
   * Non-forward compatible changes should never be added in patch releases.
   *
   * Do not make any changes that break backwards compatibility, which would
   * prevent the new reader from reading ORC files generated by any released
   * version of Hive.
   */
  public enum Version {
    V_0_11("0.11", 0, 11),
    V_0_12("0.12", 0, 12),

    /**
     * Do not use this format except for testing. It will not be compatible
     * with other versions of the software. While we iterate on the ORC 2.0
     * format, we will make incompatible format changes under this version
     * without providing any forward or backward compatibility.
     *
     * When 2.0 is released, this version identifier will be completely removed.
     */
    UNSTABLE_PRE_2_0("UNSTABLE-PRE-2.0", 1, 9999),

    /**
     * The generic identifier for all unknown versions.
     */
    FUTURE("future", Integer.MAX_VALUE, Integer.MAX_VALUE);

    public static final Version CURRENT = V_0_12;

    private final String name;
    private final int major;
    private final int minor;

    Version(String name, int major, int minor) {
      this.name = name;
      this.major = major;
      this.minor = minor;
    }

    public static Version byName(String name) {
      for(Version version: values()) {
        if (version.name.equals(name)) {
          return version;
        }
      }
      throw new IllegalArgumentException("Unknown ORC version " + name);
    }

    /**
     * Get the human readable name for the version.
     */
    public String getName() {
      return name;
    }

    /**
     * Get the major version number.
     */
    public int getMajor() {
      return major;
    }

    /**
     * Get the minor version number.
     */
    public int getMinor() {
      return minor;
    }
  }

  public enum WriterImplementation {
    ORC_JAVA(0), // ORC Java writer
    ORC_CPP(1),  // ORC C++ writer
    PRESTO(2),   // Presto writer
    SCRITCHLEY_GO(3), // Go writer from https://github.com/scritchley/orc
    UNKNOWN(Integer.MAX_VALUE);

    private final int id;

    WriterImplementation(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static WriterImplementation from(int id) {
      WriterImplementation[] values = values();
      if (id >= 0 && id < values.length - 1) {
        return values[id];
      }
      return UNKNOWN;
    }
  }

  /**
   * Records the version of the writer in terms of which bugs have been fixed.
   * When you fix bugs in the writer (or make substantial changes) that don't
   * change the file format, add a new version here instead of Version.
   *
   * The ids are assigned sequentially from 6 per a WriterImplementation so that
   * readers that predate ORC-202 treat the other writers correctly.
   */
  public enum WriterVersion {
    // Java ORC Writer
    ORIGINAL(WriterImplementation.ORC_JAVA, 0),
    HIVE_8732(WriterImplementation.ORC_JAVA, 1), // fixed stripe/file maximum
                                                 // statistics & string statistics
                                                 // use utf8 for min/max
    HIVE_4243(WriterImplementation.ORC_JAVA, 2), // use real column names from
                                                 // Hive tables
    HIVE_12055(WriterImplementation.ORC_JAVA, 3), // vectorized writer
    HIVE_13083(WriterImplementation.ORC_JAVA, 4), // decimals write present stream correctly
    ORC_101(WriterImplementation.ORC_JAVA, 5),   // bloom filters use utf8
    ORC_135(WriterImplementation.ORC_JAVA, 6),   // timestamp stats use utc
    ORC_203(WriterImplementation.ORC_JAVA, 7),   // trim long strings & record they were trimmed
    ORC_14(WriterImplementation.ORC_JAVA, 8),    // column encryption added

    // C++ ORC Writer
    ORC_CPP_ORIGINAL(WriterImplementation.ORC_CPP, 6),

    // Presto Writer
    PRESTO_ORIGINAL(WriterImplementation.PRESTO, 6),

    // Scritchley Go Writer
    SCRITCHLEY_GO_ORIGINAL(WriterImplementation.SCRITCHLEY_GO, 6),

    // Don't use any magic numbers here except for the below:
    FUTURE(WriterImplementation.UNKNOWN, Integer.MAX_VALUE); // a version from a future writer

    private final int id;
    private final WriterImplementation writer;

    public WriterImplementation getWriterImplementation() {
      return writer;
    }

    public int getId() {
      return id;
    }

    WriterVersion(WriterImplementation writer, int id) {
      this.writer = writer;
      this.id = id;
    }

    private static final WriterVersion[][] values =
        new WriterVersion[WriterImplementation.values().length][];

    static {
      for(WriterVersion v: WriterVersion.values()) {
        WriterImplementation writer = v.writer;
        if (writer != WriterImplementation.UNKNOWN) {
          if (values[writer.id] == null) {
            values[writer.id] = new WriterVersion[WriterVersion.values().length];
          }
          if (values[writer.id][v.id] != null) {
            throw new IllegalArgumentException("Duplicate WriterVersion id " + v);
          }
          values[writer.id][v.id] = v;
        }
      }
    }

    /**
     * Convert the integer from OrcProto.PostScript.writerVersion
     * to the enumeration with unknown versions being mapped to FUTURE.
     * @param writer the writer implementation
     * @param val the serialized writer version
     * @return the corresponding enumeration value
     */
    public static WriterVersion from(WriterImplementation writer, int val) {
      if (writer == WriterImplementation.UNKNOWN) {
        return FUTURE;
      }
      if (writer != WriterImplementation.ORC_JAVA && val < 6) {
        throw new IllegalArgumentException("ORC File with illegval version " +
            val + " for writer " + writer);
      }
      WriterVersion[] versions = values[writer.id];
      if (val < 0 || versions.length < val) {
        return FUTURE;
      }
      WriterVersion result = versions[val];
      return result == null ? FUTURE : result;
    }

    /**
     * Does this file include the given fix or come from a different writer?
     * @param fix the required fix
     * @return true if the required fix is present
     */
    public boolean includes(WriterVersion fix) {
      return writer != fix.writer || id >= fix.id;
    }
  }

  /**
   * The WriterVersion for this version of the software.
   */
  public static final WriterVersion CURRENT_WRITER = WriterVersion.ORC_14;

  public enum EncodingStrategy {
    SPEED, COMPRESSION
  }

  public enum CompressionStrategy {
    SPEED, COMPRESSION
  }

  // unused
  protected OrcFile() {}

  public static class ReaderOptions {
    private final Configuration conf;
    private FileSystem filesystem;
    private long maxLength = Long.MAX_VALUE;
    private OrcTail orcTail;
    private HadoopShims.KeyProvider keyProvider;
    // TODO: We can generalize FileMetada interface. Make OrcTail implement FileMetadata interface
    // and remove this class altogether. Both footer caching and llap caching just needs OrcTail.
    // For now keeping this around to avoid complex surgery
    private FileMetadata fileMetadata;
    private boolean useUTCTimestamp;

    public ReaderOptions(Configuration conf) {
      this.conf = conf;
    }

    public ReaderOptions filesystem(FileSystem fs) {
      this.filesystem = fs;
      return this;
    }

    public ReaderOptions maxLength(long val) {
      maxLength = val;
      return this;
    }

    public ReaderOptions orcTail(OrcTail tail) {
      this.orcTail = tail;
      return this;
    }

    /**
     * Set the KeyProvider to override the default for getting keys.
     * @param provider
     * @return
     */
    public ReaderOptions setKeyProvider(HadoopShims.KeyProvider provider) {
      this.keyProvider = provider;
      return this;
    }

    public Configuration getConfiguration() {
      return conf;
    }

    public FileSystem getFilesystem() {
      return filesystem;
    }

    public long getMaxLength() {
      return maxLength;
    }

    public OrcTail getOrcTail() {
      return orcTail;
    }

    public HadoopShims.KeyProvider getKeyProvider() {
      return keyProvider;
    }

    public ReaderOptions fileMetadata(final FileMetadata metadata) {
      fileMetadata = metadata;
      return this;
    }

    public FileMetadata getFileMetadata() {
      return fileMetadata;
    }

    public ReaderOptions useUTCTimestamp(boolean value) {
      useUTCTimestamp = value;
      return this;
    }

    public boolean getUseUTCTimestamp() {
      return useUTCTimestamp;
    }

  }

  public static ReaderOptions readerOptions(Configuration conf) {
    return new ReaderOptions(conf);
  }

  public static Reader createReader(Path path,
                                    ReaderOptions options) throws IOException {
    return new ReaderImpl(path, options);
  }

  public interface WriterContext {
    Writer getWriter();
  }

  public interface WriterCallback {
    void preStripeWrite(WriterContext context) throws IOException;
    void preFooterWrite(WriterContext context) throws IOException;
  }

  public enum BloomFilterVersion {
    // Include both the BLOOM_FILTER and BLOOM_FILTER_UTF8 streams to support
    // both old and new readers.
    ORIGINAL("original"),
    // Only include the BLOOM_FILTER_UTF8 streams that consistently use UTF8.
    // See ORC-101
    UTF8("utf8");

    private final String id;
    BloomFilterVersion(String id) {
      this.id = id;
    }

    public String toString() {
      return id;
    }

    public static BloomFilterVersion fromString(String s) {
      for (BloomFilterVersion version: values()) {
        if (version.id.equals(s)) {
          return version;
        }
      }
      throw new IllegalArgumentException("Unknown BloomFilterVersion " + s);
    }
  }

  /**
   * An internal class that describes how to encrypt a column.
   */
  public static class EncryptionOption {
    private final String columnNames;
    private final String keyName;
    private final String mask;
    private final String[] maskParameters;

    EncryptionOption(String columnNames, String keyName, String mask,
                     String... maskParams) {
      this.columnNames = columnNames;
      this.keyName = keyName;
      this.mask = mask;
      this.maskParameters = maskParams;
    }

    public String getColumnNames() {
      return columnNames;
    }

    public String getKeyName() {
      return keyName;
    }

    public String getMask() {
      return mask;
    }

    public String[] getMaskParameters() {
      return maskParameters;
    }
  }

  /**
   * Options for creating ORC file writers.
   */
  public static class WriterOptions implements Cloneable {
    private final Configuration configuration;
    private FileSystem fileSystemValue = null;
    private TypeDescription schema = null;
    private long stripeSizeValue;
    private long blockSizeValue;
    private int rowIndexStrideValue;
    private int bufferSizeValue;
    private boolean enforceBufferSize = false;
    private boolean blockPaddingValue;
    private CompressionKind compressValue;
    private MemoryManager memoryManagerValue;
    private Version versionValue;
    private WriterCallback callback;
    private EncodingStrategy encodingStrategy;
    private CompressionStrategy compressionStrategy;
    private double paddingTolerance;
    private String bloomFilterColumns;
    private double bloomFilterFpp;
    private BloomFilterVersion bloomFilterVersion;
    private PhysicalWriter physicalWriter;
    private WriterVersion writerVersion = CURRENT_WRITER;
    private boolean useUTCTimestamp;
    private boolean overwrite;
    private boolean writeVariableLengthBlocks;
    private HadoopShims shims;
    private String directEncodingColumns;
    private List<EncryptionOption> encryption = new ArrayList<>();
    private HadoopShims.KeyProvider provider;

    protected WriterOptions(Properties tableProperties, Configuration conf) {
      configuration = conf;
      memoryManagerValue = getStaticMemoryManager(conf);
      overwrite = OrcConf.OVERWRITE_OUTPUT_FILE.getBoolean(tableProperties, conf);
      stripeSizeValue = OrcConf.STRIPE_SIZE.getLong(tableProperties, conf);
      blockSizeValue = OrcConf.BLOCK_SIZE.getLong(tableProperties, conf);
      rowIndexStrideValue =
          (int) OrcConf.ROW_INDEX_STRIDE.getLong(tableProperties, conf);
      bufferSizeValue = (int) OrcConf.BUFFER_SIZE.getLong(tableProperties,
          conf);
      blockPaddingValue =
          OrcConf.BLOCK_PADDING.getBoolean(tableProperties, conf);
      compressValue =
          CompressionKind.valueOf(OrcConf.COMPRESS.getString(tableProperties,
              conf).toUpperCase());
      enforceBufferSize = OrcConf.ENFORCE_COMPRESSION_BUFFER_SIZE.getBoolean(tableProperties, conf);
      String versionName = OrcConf.WRITE_FORMAT.getString(tableProperties,
          conf);
      versionValue = Version.byName(versionName);
      String enString = OrcConf.ENCODING_STRATEGY.getString(tableProperties,
          conf);
      encodingStrategy = EncodingStrategy.valueOf(enString);

      String compString =
          OrcConf.COMPRESSION_STRATEGY.getString(tableProperties, conf);
      compressionStrategy = CompressionStrategy.valueOf(compString);

      paddingTolerance =
          OrcConf.BLOCK_PADDING_TOLERANCE.getDouble(tableProperties, conf);

      bloomFilterColumns = OrcConf.BLOOM_FILTER_COLUMNS.getString(tableProperties,
          conf);
      bloomFilterFpp = OrcConf.BLOOM_FILTER_FPP.getDouble(tableProperties,
          conf);
      bloomFilterVersion =
          BloomFilterVersion.fromString(
              OrcConf.BLOOM_FILTER_WRITE_VERSION.getString(tableProperties,
                  conf));
      shims = HadoopShimsFactory.get();
      writeVariableLengthBlocks =
          OrcConf.WRITE_VARIABLE_LENGTH_BLOCKS.getBoolean(tableProperties,conf);
      directEncodingColumns = OrcConf.DIRECT_ENCODING_COLUMNS.getString(
          tableProperties, conf);
    }

    /**
     * @return a SHALLOW clone
     */
    public WriterOptions clone() {
      try {
        return (WriterOptions) super.clone();
      } catch (CloneNotSupportedException ex) {
        throw new AssertionError("Expected super.clone() to work");
      }
    }

    /**
     * Provide the filesystem for the path, if the client has it available.
     * If it is not provided, it will be found from the path.
     */
    public WriterOptions fileSystem(FileSystem value) {
      fileSystemValue = value;
      return this;
    }

    /**
     * If the output file already exists, should it be overwritten?
     * If it is not provided, write operation will fail if the file already exists.
     */
    public WriterOptions overwrite(boolean value) {
      overwrite = value;
      return this;
    }

    /**
     * Set the stripe size for the file. The writer stores the contents of the
     * stripe in memory until this memory limit is reached and the stripe
     * is flushed to the HDFS file and the next stripe started.
     */
    public WriterOptions stripeSize(long value) {
      stripeSizeValue = value;
      return this;
    }

    /**
     * Set the file system block size for the file. For optimal performance,
     * set the block size to be multiple factors of stripe size.
     */
    public WriterOptions blockSize(long value) {
      blockSizeValue = value;
      return this;
    }

    /**
     * Set the distance between entries in the row index. The minimum value is
     * 1000 to prevent the index from overwhelming the data. If the stride is
     * set to 0, no indexes will be included in the file.
     */
    public WriterOptions rowIndexStride(int value) {
      rowIndexStrideValue = value;
      return this;
    }

    /**
     * The size of the memory buffers used for compressing and storing the
     * stripe in memory. NOTE: ORC writer may choose to use smaller buffer
     * size based on stripe size and number of columns for efficient stripe
     * writing and memory utilization. To enforce writer to use the requested
     * buffer size use enforceBufferSize().
     */
    public WriterOptions bufferSize(int value) {
      bufferSizeValue = value;
      return this;
    }

    /**
     * Enforce writer to use requested buffer size instead of estimating
     * buffer size based on stripe size and number of columns.
     * See bufferSize() method for more info.
     * Default: false
     */
    public WriterOptions enforceBufferSize() {
      enforceBufferSize = true;
      return this;
    }

    /**
     * Sets whether the HDFS blocks are padded to prevent stripes from
     * straddling blocks. Padding improves locality and thus the speed of
     * reading, but costs space.
     */
    public WriterOptions blockPadding(boolean value) {
      blockPaddingValue = value;
      return this;
    }

    /**
     * Sets the encoding strategy that is used to encode the data.
     */
    public WriterOptions encodingStrategy(EncodingStrategy strategy) {
      encodingStrategy = strategy;
      return this;
    }

    /**
     * Sets the tolerance for block padding as a percentage of stripe size.
     */
    public WriterOptions paddingTolerance(double value) {
      paddingTolerance = value;
      return this;
    }

    /**
     * Comma separated values of column names for which bloom filter is to be created.
     */
    public WriterOptions bloomFilterColumns(String columns) {
      bloomFilterColumns = columns;
      return this;
    }

    /**
     * Specify the false positive probability for bloom filter.
     *
     * @param fpp - false positive probability
     * @return this
     */
    public WriterOptions bloomFilterFpp(double fpp) {
      bloomFilterFpp = fpp;
      return this;
    }

    /**
     * Sets the generic compression that is used to compress the data.
     */
    public WriterOptions compress(CompressionKind value) {
      compressValue = value;
      return this;
    }

    /**
     * Set the schema for the file. This is a required parameter.
     *
     * @param schema the schema for the file.
     * @return this
     */
    public WriterOptions setSchema(TypeDescription schema) {
      this.schema = schema;
      return this;
    }

    /**
     * Sets the version of the file that will be written.
     */
    public WriterOptions version(Version value) {
      versionValue = value;
      return this;
    }

    /**
     * Add a listener for when the stripe and file are about to be closed.
     *
     * @param callback the object to be called when the stripe is closed
     * @return this
     */
    public WriterOptions callback(WriterCallback callback) {
      this.callback = callback;
      return this;
    }

    /**
     * Set the version of the bloom filters to write.
     */
    public WriterOptions bloomFilterVersion(BloomFilterVersion version) {
      this.bloomFilterVersion = version;
      return this;
    }

    /**
     * Change the physical writer of the ORC file.
     * <p>
     * SHOULD ONLY BE USED BY LLAP.
     *
     * @param writer the writer to control the layout and persistence
     * @return this
     */
    public WriterOptions physicalWriter(PhysicalWriter writer) {
      this.physicalWriter = writer;
      return this;
    }

    /**
     * A package local option to set the memory manager.
     */
    public WriterOptions memory(MemoryManager value) {
      memoryManagerValue = value;
      return this;
    }

    /**
     * Should the ORC file writer use HDFS variable length blocks, if they
     * are available?
     * @param value the new value
     * @return this
     */
    public WriterOptions writeVariableLengthBlocks(boolean value) {
      writeVariableLengthBlocks = value;
      return this;
    }

    /**
     * Set the HadoopShims to use.
     * This is only for testing.
     * @param value the new value
     * @return this
     */
    public WriterOptions setShims(HadoopShims value) {
      this.shims = value;
      return this;
    }

    /**
     * Manually set the writer version.
     * This is an internal API.
     *
     * @param version the version to write
     * @return this
     */
    protected WriterOptions writerVersion(WriterVersion version) {
      if (version == WriterVersion.FUTURE) {
        throw new IllegalArgumentException("Can't write a future version.");
      }
      this.writerVersion = version;
      return this;
    }

    /**
     * Manually set the time zone for the writer to utc.
     * If not defined, system time zone is assumed.
     */
    public WriterOptions useUTCTimestamp(boolean value) {
      useUTCTimestamp = value;
      return this;
    }

    /**
     * Set the comma-separated list of columns that should be direct encoded.
     * @param value the value to set
     * @return this
     */
    public WriterOptions directEncodingColumns(String value) {
      directEncodingColumns = value;
      return this;
    }

    /*
     * Encrypt a set of columns with a key.
     * For readers without access to the key, they will read nulls.
     * @param columnNames the columns to encrypt
     * @param keyName the key name to encrypt the data with
     * @return this
     */
    public WriterOptions encryptColumn(String columnNames,
                                       String keyName) {
      return encryptColumn(columnNames, keyName,
          DataMask.Standard.NULLIFY.getName());
    }

    /**
     * Encrypt a set of columns with a key.
     * The data is also masked and stored unencrypted in the file. Readers
     * without access to the key will instead get the masked data.
     * @param columnNames the column names to encrypt
     * @param keyName the key name to encrypt the data with
     * @param mask the kind of masking
     * @param maskParameters the parameters to the mask
     * @return this
     */
    public WriterOptions encryptColumn(String columnNames,
                                       String keyName,
                                       String mask,
                                       String... maskParameters) {
      encryption.add(new EncryptionOption(columnNames, keyName, mask,
          maskParameters));
      return this;
    }

    /**
     * Set a different mask on a subtree that is already being encrypted.
     * @param columnNames the column names to change the mask on
     * @param mask the name of the mask
     * @param maskParameters the parameters for the mask
     * @return this
     */
    public WriterOptions maskColumn(String columnNames,
                                    String mask,
                                    String... maskParameters) {
      encryption.add(new EncryptionOption(columnNames, null,
          mask, maskParameters));
      return this;
    }

    /**
     * Set the key provider for
     * @param provider
     * @return
     */
    public WriterOptions setKeyProvider(HadoopShims.KeyProvider provider) {
      this.provider = provider;
      return this;
    }

    public HadoopShims.KeyProvider getKeyProvider() {
      return provider;
    }

    public boolean getBlockPadding() {
      return blockPaddingValue;
    }

    public long getBlockSize() {
      return blockSizeValue;
    }

    public String getBloomFilterColumns() {
      return bloomFilterColumns;
    }

    public boolean getOverwrite() {
      return overwrite;
    }

    public FileSystem getFileSystem() {
      return fileSystemValue;
    }

    public Configuration getConfiguration() {
      return configuration;
    }

    public TypeDescription getSchema() {
      return schema;
    }

    public long getStripeSize() {
      return stripeSizeValue;
    }

    public CompressionKind getCompress() {
      return compressValue;
    }

    public WriterCallback getCallback() {
      return callback;
    }

    public Version getVersion() {
      return versionValue;
    }

    public MemoryManager getMemoryManager() {
      return memoryManagerValue;
    }

    public int getBufferSize() {
      return bufferSizeValue;
    }

    public boolean isEnforceBufferSize() {
      return enforceBufferSize;
    }

    public int getRowIndexStride() {
      return rowIndexStrideValue;
    }

    public CompressionStrategy getCompressionStrategy() {
      return compressionStrategy;
    }

    public EncodingStrategy getEncodingStrategy() {
      return encodingStrategy;
    }

    public double getPaddingTolerance() {
      return paddingTolerance;
    }

    public double getBloomFilterFpp() {
      return bloomFilterFpp;
    }

    public BloomFilterVersion getBloomFilterVersion() {
      return bloomFilterVersion;
    }

    public PhysicalWriter getPhysicalWriter() {
      return physicalWriter;
    }

    public WriterVersion getWriterVersion() {
      return writerVersion;
    }

    public boolean getWriteVariableLengthBlocks() {
      return writeVariableLengthBlocks;
    }

    public HadoopShims getHadoopShims() {
      return shims;
    }

    public boolean getUseUTCTimestamp() {
      return useUTCTimestamp;
    }

    public String getDirectEncodingColumns() {
      return directEncodingColumns;
    }

    public List<EncryptionOption> getEncryption() {
      return encryption;
    }
  }

  /**
   * Create a set of writer options based on a configuration.
   * @param conf the configuration to use for values
   * @return A WriterOptions object that can be modified
   */
  public static WriterOptions writerOptions(Configuration conf) {
    return new WriterOptions(null, conf);
  }

  /**
   * Create a set of write options based on a set of table properties and
   * configuration.
   * @param tableProperties the properties of the table
   * @param conf the configuration of the query
   * @return a WriterOptions object that can be modified
   */
  public static WriterOptions writerOptions(Properties tableProperties,
                                            Configuration conf) {
    return new WriterOptions(tableProperties, conf);
  }

  private static ThreadLocal<MemoryManager> memoryManager = null;

  private static synchronized MemoryManager getStaticMemoryManager(
      final Configuration conf) {
    if (memoryManager == null) {
      memoryManager = new ThreadLocal<MemoryManager>() {
        @Override
        protected MemoryManager initialValue() {
          return new MemoryManagerImpl(conf);
        }
      };
    }
    return memoryManager.get();
  }

  /**
   * Create an ORC file writer. This is the public interface for creating
   * writers going forward and new options will only be added to this method.
   * @param path filename to write to
   * @param opts the options
   * @return a new ORC file writer
   * @throws IOException
   */
  public static Writer createWriter(Path path,
                                    WriterOptions opts
                                    ) throws IOException {
    FileSystem fs = opts.getFileSystem() == null ?
        path.getFileSystem(opts.getConfiguration()) : opts.getFileSystem();
    switch (opts.getVersion()) {
      case V_0_11:
      case V_0_12:
        return new WriterImpl(fs, path, opts);
      case UNSTABLE_PRE_2_0:
        return new WriterImplV2(fs, path, opts);
      default:
        throw new IllegalArgumentException("Unknown version " +
            opts.getVersion());
    }
  }

  /**
   * Do we understand the version in the reader?
   * @param path the path of the file
   * @param reader the ORC file reader
   * @return is the version understood by this writer?
   */
  static boolean understandFormat(Path path, Reader reader) {
    if (reader.getFileVersion() == Version.FUTURE) {
      LOG.info("Can't merge {} because it has a future version.", path);
      return false;
    }
    if (reader.getWriterVersion() == WriterVersion.FUTURE) {
      LOG.info("Can't merge {} because it has a future writerVersion.", path);
      return false;
    }
    return true;
  }

  /**
   * Is the new reader compatible with the file that is being written?
   * @param schema the writer schema
   * @param fileVersion the writer fileVersion
   * @param writerVersion the writer writerVersion
   * @param rowIndexStride the row index stride
   * @param compression the compression that was used
   * @param userMetadata the user metadata
   * @param path the new path name for warning messages
   * @param reader the new reader
   * @return is the reader compatible with the previous ones?
   */
  static boolean readerIsCompatible(TypeDescription schema,
                                    Version fileVersion,
                                    WriterVersion writerVersion,
                                    int rowIndexStride,
                                    CompressionKind compression,
                                    Map<String, ByteBuffer> userMetadata,
                                    Path path,
                                    Reader reader) {
    // now we have to check compatibility
    if (!reader.getSchema().equals(schema)) {
      LOG.info("Can't merge {} because of different schemas {} vs {}",
          path, reader.getSchema(), schema);
      return false;
    }
    if (reader.getCompressionKind() != compression) {
      LOG.info("Can't merge {} because of different compression {} vs {}",
          path, reader.getCompressionKind(), compression);
      return false;
    }
    if (reader.getFileVersion() != fileVersion) {
      LOG.info("Can't merge {} because of different file versions {} vs {}",
          path, reader.getFileVersion(), fileVersion);
      return false;
    }
    if (reader.getWriterVersion() != writerVersion) {
      LOG.info("Can't merge {} because of different writer versions {} vs {}",
          path, reader.getFileVersion(), fileVersion);
      return false;
    }
    if (reader.getRowIndexStride() != rowIndexStride) {
      LOG.info("Can't merge {} because of different row index strides {} vs {}",
          path, reader.getRowIndexStride(), rowIndexStride);
      return false;
    }
    for(String key: reader.getMetadataKeys()) {
      if (userMetadata.containsKey(key)) {
        ByteBuffer currentValue = userMetadata.get(key);
        ByteBuffer newValue = reader.getMetadataValue(key);
        if (!newValue.equals(currentValue)) {
          LOG.info("Can't merge {} because of different user metadata {}", path,
              key);
          return false;
        }
      }
    }
    return true;
  }

  static void mergeMetadata(Map<String,ByteBuffer> metadata,
                            Reader reader) {
    for(String key: reader.getMetadataKeys()) {
      metadata.put(key, reader.getMetadataValue(key));
    }
  }

  /**
   * Merges multiple ORC files that all have the same schema to produce
   * a single ORC file.
   * The merge will reject files that aren't compatible with the merged file
   * so the output list may be shorter than the input list.
   * The stripes are copied as serialized byte buffers.
   * The user metadata are merged and files that disagree on the value
   * associated with a key will be rejected.
   *
   * @param outputPath the output file
   * @param options the options for writing with although the options related
   *                to the input files' encodings are overridden
   * @param inputFiles the list of files to merge
   * @return the list of files that were successfully merged
   * @throws IOException
   */
  public static List<Path> mergeFiles(Path outputPath,
                                      WriterOptions options,
                                      List<Path> inputFiles) throws IOException {
    Writer output = null;
    final Configuration conf = options.getConfiguration();
    try {
      byte[] buffer = new byte[0];
      TypeDescription schema = null;
      CompressionKind compression = null;
      int bufferSize = 0;
      Version fileVersion = null;
      WriterVersion writerVersion = null;
      int rowIndexStride = 0;
      List<Path> result = new ArrayList<>(inputFiles.size());
      Map<String, ByteBuffer> userMetadata = new HashMap<>();

      for (Path input : inputFiles) {
        FileSystem fs = input.getFileSystem(conf);
        Reader reader = createReader(input,
            readerOptions(options.getConfiguration()).filesystem(fs));

        if (!understandFormat(input, reader)) {
          continue;
        } else if (schema == null) {
          // if this is the first file that we are including, grab the values
          schema = reader.getSchema();
          compression = reader.getCompressionKind();
          bufferSize = reader.getCompressionSize();
          rowIndexStride = reader.getRowIndexStride();
          fileVersion = reader.getFileVersion();
          writerVersion = reader.getWriterVersion();
          options.bufferSize(bufferSize)
              .version(fileVersion)
              .writerVersion(writerVersion)
              .compress(compression)
              .rowIndexStride(rowIndexStride)
              .setSchema(schema);
          if (compression != CompressionKind.NONE) {
            options.enforceBufferSize().bufferSize(bufferSize);
          }
          mergeMetadata(userMetadata, reader);
          output = createWriter(outputPath, options);
        } else if (!readerIsCompatible(schema, fileVersion, writerVersion,
            rowIndexStride, compression, userMetadata, input, reader)) {
          continue;
        } else {
          mergeMetadata(userMetadata, reader);
          if (bufferSize < reader.getCompressionSize()) {
            bufferSize = reader.getCompressionSize();
            ((WriterInternal) output).increaseCompressionSize(bufferSize);
          }
        }
        List<OrcProto.StripeStatistics> statList =
            reader.getOrcProtoStripeStatistics();
        try (FSDataInputStream inputStream = fs.open(input)) {
          int stripeNum = 0;
          result.add(input);

          for (StripeInformation stripe : reader.getStripes()) {
            int length = (int) stripe.getLength();
            if (buffer.length < length) {
              buffer = new byte[length];
            }
            long offset = stripe.getOffset();
            inputStream.readFully(offset, buffer, 0, length);
            output.appendStripe(buffer, 0, length, stripe, statList.get(stripeNum++));
          }
        }
      }
      if (output != null) {
        for (Map.Entry<String, ByteBuffer> entry : userMetadata.entrySet()) {
          output.addUserMetadata(entry.getKey(), entry.getValue());
        }
        output.close();
      }
      return result;
    } catch (IOException ioe) {
      if (output != null) {
        try {
          output.close();
        } catch (Throwable t) {
          // PASS
        }
        try {
          FileSystem fs = options.getFileSystem() == null ?
              outputPath.getFileSystem(conf) : options.getFileSystem();
          fs.delete(outputPath, false);
        } catch (Throwable t) {
          // PASS
        }
      }
      throw ioe;
    }
  }
}
