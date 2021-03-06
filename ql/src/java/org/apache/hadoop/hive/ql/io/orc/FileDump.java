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
package org.apache.hadoop.hive.ql.io.orc;

import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndex;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.RowIndexEntry;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONWriter;

/**
 * A tool for printing out the file structure of ORC files.
 */
public final class FileDump {
  private static final String ROWINDEX_PREFIX = "--rowindex=";

  // not used
  private FileDump() {}

  public static void main(String[] args) throws Exception {
    Configuration conf = new Configuration();

    List<Integer> rowIndexCols = null;
    Options opts = createOptions();
    CommandLine cli = new GnuParser().parse(opts, args);

    if (cli.hasOption('h')) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("orcfiledump", opts);
      return;
    }

    boolean dumpData = cli.hasOption('d');
    if (cli.hasOption("rowindex")) {
      String[] colStrs = cli.getOptionValue("rowindex").split(",");
      rowIndexCols = new ArrayList<Integer>(colStrs.length);
      for (String colStr : colStrs) {
        rowIndexCols.add(Integer.parseInt(colStr));
      }
    }

    String[] files = cli.getArgs();
    if (dumpData) printData(Arrays.asList(files), conf);
    else printMetaData(Arrays.asList(files), conf, rowIndexCols);
  }

  private static void printData(List<String> files, Configuration conf) throws IOException,
      JSONException {
    for (String file : files) {
      printJsonData(conf, file);
    }
  }

  private static void printMetaData(List<String> files, Configuration conf,
                                    List<Integer> rowIndexCols) throws IOException {
    for (String filename : files) {
      System.out.println("Structure for " + filename);
      Path path = new Path(filename);
      Reader reader = OrcFile.createReader(path, OrcFile.readerOptions(conf));
      System.out.println("File Version: " + reader.getFileVersion().getName() +
                         " with " + reader.getWriterVersion());
      RecordReaderImpl rows = (RecordReaderImpl) reader.rows();
      System.out.println("Rows: " + reader.getNumberOfRows());
      System.out.println("Compression: " + reader.getCompression());
      if (reader.getCompression() != CompressionKind.NONE) {
        System.out.println("Compression size: " + reader.getCompressionSize());
      }
      System.out.println("Type: " + reader.getObjectInspector().getTypeName());
      System.out.println("\nStripe Statistics:");
      Metadata metadata = reader.getMetadata();
      for (int n = 0; n < metadata.getStripeStatistics().size(); n++) {
        System.out.println("  Stripe " + (n + 1) + ":");
        StripeStatistics ss = metadata.getStripeStatistics().get(n);
        for (int i = 0; i < ss.getColumnStatistics().length; ++i) {
          System.out.println("    Column " + i + ": " +
              ss.getColumnStatistics()[i].toString());
        }
      }
      ColumnStatistics[] stats = reader.getStatistics();
      System.out.println("\nFile Statistics:");
      for(int i=0; i < stats.length; ++i) {
        System.out.println("  Column " + i + ": " + stats[i].toString());
      }
      System.out.println("\nStripes:");
      int stripeIx = -1;
      for (StripeInformation stripe : reader.getStripes()) {
        ++stripeIx;
        long stripeStart = stripe.getOffset();
        System.out.println("  Stripe: " + stripe.toString());
        OrcProto.StripeFooter footer = rows.readStripeFooter(stripe);
        long sectionStart = stripeStart;
        for(OrcProto.Stream section: footer.getStreamsList()) {
          System.out.println("    Stream: column " + section.getColumn() +
            " section " + section.getKind() + " start: " + sectionStart +
            " length " + section.getLength());
          sectionStart += section.getLength();
        }
        for (int i = 0; i < footer.getColumnsCount(); ++i) {
          OrcProto.ColumnEncoding encoding = footer.getColumns(i);
          StringBuilder buf = new StringBuilder();
          buf.append("    Encoding column ");
          buf.append(i);
          buf.append(": ");
          buf.append(encoding.getKind());
          if (encoding.getKind() == OrcProto.ColumnEncoding.Kind.DICTIONARY ||
              encoding.getKind() == OrcProto.ColumnEncoding.Kind.DICTIONARY_V2) {
            buf.append("[");
            buf.append(encoding.getDictionarySize());
            buf.append("]");
          }
          System.out.println(buf);
        }
        if (rowIndexCols != null) {
          RowIndex[] indices = rows.readRowIndex(stripeIx);
          for (int col : rowIndexCols) {
            StringBuilder buf = new StringBuilder();
            buf.append("    Row group index column ").append(col).append(":");
            RowIndex index = null;
            if ((col >= indices.length) || ((index = indices[col]) == null)) {
              buf.append(" not found\n");
              continue;
            }
            for (int entryIx = 0; entryIx < index.getEntryCount(); ++entryIx) {
              buf.append("\n      Entry ").append(entryIx).append(":");
              RowIndexEntry entry = index.getEntry(entryIx);
              if (entry == null) {
                buf.append("unknown\n");
                continue;
              }
              OrcProto.ColumnStatistics colStats = entry.getStatistics();
              if (colStats == null) {
                buf.append("no stats at ");
              } else {
                ColumnStatistics cs = ColumnStatisticsImpl.deserialize(colStats);
                Object min = RecordReaderImpl.getMin(cs), max = RecordReaderImpl.getMax(cs);
                buf.append(" count: ").append(cs.getNumberOfValues());
                buf.append(" min: ").append(min);
                buf.append(" max: ").append(max);
              }
              buf.append(" positions: ");
              for (int posIx = 0; posIx < entry.getPositionsCount(); ++posIx) {
                if (posIx != 0) {
                  buf.append(",");
                }
                buf.append(entry.getPositions(posIx));
              }
            }
            System.out.println(buf);
          }
        }
      }

      FileSystem fs = path.getFileSystem(conf);
      long fileLen = fs.getContentSummary(path).getLength();
      long paddedBytes = getTotalPaddingSize(reader);
      // empty ORC file is ~45 bytes. Assumption here is file length always >0
      double percentPadding = ((double) paddedBytes / (double) fileLen) * 100;
      DecimalFormat format = new DecimalFormat("##.##");
      System.out.println("\nFile length: " + fileLen + " bytes");
      System.out.println("Padding length: " + paddedBytes + " bytes");
      System.out.println("Padding ratio: " + format.format(percentPadding) + "%");
      rows.close();
    }
  }

  private static long getTotalPaddingSize(Reader reader) throws IOException {
    long paddedBytes = 0;
    List<org.apache.hadoop.hive.ql.io.orc.StripeInformation> stripes = reader.getStripes();
    for (int i = 1; i < stripes.size(); i++) {
      long prevStripeOffset = stripes.get(i - 1).getOffset();
      long prevStripeLen = stripes.get(i - 1).getLength();
      paddedBytes += stripes.get(i).getOffset() - (prevStripeOffset + prevStripeLen);
    }
    return paddedBytes;
  }

  static Options createOptions() {
    Options result = new Options();

    // add -d and --data to print the rows
    result.addOption(OptionBuilder
        .withLongOpt("data")
        .withDescription("Should the data be printed")
        .create('d'));

    result.addOption(OptionBuilder
        .withLongOpt("help")
        .withDescription("print help message")
        .create('h'));

    result.addOption(OptionBuilder
        .withLongOpt("rowindex")
        .withArgName("comma separated list of column ids for which row index should be printed")
        .withDescription("Dump stats for column number(s)")
        .hasArg()
        .create());


    return result;
  }

  private static void printMap(JSONWriter writer,
                               Map<Object, Object> obj,
                               List<OrcProto.Type> types,
                               OrcProto.Type type
  ) throws IOException, JSONException {
    writer.array();
    int keyType = type.getSubtypes(0);
    int valueType = type.getSubtypes(1);
    for(Map.Entry<Object,Object> item: obj.entrySet()) {
      writer.object();
      writer.key("_key");
      printObject(writer, item.getKey(), types, keyType);
      writer.key("_value");
      printObject(writer, item.getValue(), types, valueType);
      writer.endObject();
    }
    writer.endArray();
  }

  private static void printList(JSONWriter writer,
                                List<Object> obj,
                                List<OrcProto.Type> types,
                                OrcProto.Type type
  ) throws IOException, JSONException {
    int subtype = type.getSubtypes(0);
    writer.array();
    for(Object item: obj) {
      printObject(writer, item, types, subtype);
    }
    writer.endArray();
  }

  private static void printUnion(JSONWriter writer,
                                 OrcUnion obj,
                                 List<OrcProto.Type> types,
                                 OrcProto.Type type
  ) throws IOException, JSONException {
    int subtype = type.getSubtypes(obj.getTag());
    printObject(writer, obj.getObject(), types, subtype);
  }

  static void printStruct(JSONWriter writer,
                          OrcStruct obj,
                          List<OrcProto.Type> types,
                          OrcProto.Type type) throws IOException, JSONException {
    writer.object();
    List<Integer> fieldTypes = type.getSubtypesList();
    for(int i=0; i < fieldTypes.size(); ++i) {
      writer.key(type.getFieldNames(i));
      printObject(writer, obj.getFieldValue(i), types, fieldTypes.get(i));
    }
    writer.endObject();
  }

  static void printObject(JSONWriter writer,
                          Object obj,
                          List<OrcProto.Type> types,
                          int typeId) throws IOException, JSONException {
    OrcProto.Type type = types.get(typeId);
    if (obj == null) {
      writer.value(null);
    } else {
      switch (type.getKind()) {
        case STRUCT:
          printStruct(writer, (OrcStruct) obj, types, type);
          break;
        case UNION:
          printUnion(writer, (OrcUnion) obj, types, type);
          break;
        case LIST:
          printList(writer, (List<Object>) obj, types, type);
          break;
        case MAP:
          printMap(writer, (Map<Object, Object>) obj, types, type);
          break;
        case BYTE:
          writer.value(((ByteWritable) obj).get());
          break;
        case SHORT:
          writer.value(((ShortWritable) obj).get());
          break;
        case INT:
          writer.value(((IntWritable) obj).get());
          break;
        case LONG:
          writer.value(((LongWritable) obj).get());
          break;
        case FLOAT:
          writer.value(((FloatWritable) obj).get());
          break;
        case DOUBLE:
          writer.value(((DoubleWritable) obj).get());
          break;
        case BOOLEAN:
          writer.value(((BooleanWritable) obj).get());
          break;
        default:
          writer.value(obj.toString());
          break;
      }
    }
  }

  static void printJsonData(Configuration conf,
                            String filename) throws IOException, JSONException {
    Path path = new Path(filename);
    Reader reader = OrcFile.createReader(path.getFileSystem(conf), path);
    OutputStreamWriter out = new OutputStreamWriter(System.out, "UTF-8");
    RecordReader rows = reader.rows(null);
    Object row = null;
    List<OrcProto.Type> types = reader.getTypes();
    while (rows.hasNext()) {
      row = rows.next(row);
      JSONWriter writer = new JSONWriter(out);
      printObject(writer, row, types, 0);
      out.write("\n");
      out.flush();
    }
  }
}
