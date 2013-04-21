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

package org.apache.sqoop.mapreduce.hcat;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DefaultStringifier;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatUtil;
import org.apache.hcatalog.data.HCatRecord;
import org.apache.hcatalog.data.schema.HCatFieldSchema;
import org.apache.hcatalog.data.schema.HCatSchema;
import org.apache.hcatalog.mapreduce.InputJobInfo;
import org.apache.sqoop.lib.SqoopRecord;
import org.apache.sqoop.mapreduce.AutoProgressMapper;
import org.apache.sqoop.mapreduce.ExportJobBase;

/**
 * A mapper that works on combined hcat splits.
 */
public class SqoopHCatExportMapper
  extends AutoProgressMapper<LongWritable, HCatRecord,
  SqoopRecord, NullWritable> {
  public static final Log LOG = LogFactory
    .getLog(SqoopHCatExportMapper.class.getName());
  private InputJobInfo jobInfo;
  private HCatSchema hCatFullTableSchema;
  private List<HCatFieldSchema> hCatSchemaFields;
  private MapWritable colTypesJava;
  private MapWritable colTypesSql;
  private SqoopRecord sqoopRecord;
  private static final String TIMESTAMP_TYPE = "java.sql.Timestamp";
  private static final String TIME_TYPE = "java.sql.Time";
  private static final String DATE_TYPE = "java.sql.Date";
  private static final String BIG_DECIMAL_TYPE = "java.math.BigDecimal";
  private static final String FLOAT_TYPE = "Float";
  private static final String DOUBLE_TYPE = "Double";
  private static final String BYTE_TYPE = "Byte";
  private static final String SHORT_TYPE = "Short";
  private static final String INTEGER_TYPE = "Integer";
  private static final String LONG_TYPE = "Long";
  private static final String BOOLEAN_TYPE = "Boolean";
  private static final String STRING_TYPE = "String";

  private static final String BYTESWRITABLE =
    "org.apache.hadoop.io.BytesWritable";
  public static final String DEBUG_HCAT_EXPORT_MAPPER_PROP =
    "sqoop.debug.export.mapper";
  private static boolean debugHCatExportMapper = false;

  @Override
  protected void setup(Context context)
    throws IOException, InterruptedException {
    super.setup(context);

    Configuration conf = context.getConfiguration();
    colTypesJava = DefaultStringifier.load(conf,
      SqoopHCatUtilities.HCAT_DB_OUTPUT_COLTYPES_JAVA, MapWritable.class);
    colTypesSql = DefaultStringifier.load(conf,
      SqoopHCatUtilities.HCAT_DB_OUTPUT_COLTYPES_SQL, MapWritable.class);
    // Instantiate a copy of the user's class to hold and parse the record.
    String recordClassName = conf.get(
      ExportJobBase.SQOOP_EXPORT_TABLE_CLASS_KEY);
    if (null == recordClassName) {
      throw new IOException("Export table class name ("
        + ExportJobBase.SQOOP_EXPORT_TABLE_CLASS_KEY
        + ") is not set!");
    }
    debugHCatExportMapper = conf.getBoolean(
      DEBUG_HCAT_EXPORT_MAPPER_PROP, false);
    try {
      Class cls = Class.forName(recordClassName, true,
        Thread.currentThread().getContextClassLoader());
      sqoopRecord = (SqoopRecord) ReflectionUtils.newInstance(cls, conf);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    }

    if (null == sqoopRecord) {
      throw new IOException("Could not instantiate object of type "
        + recordClassName);
    }

    String inputJobInfoStr = conf.get(HCatConstants.HCAT_KEY_JOB_INFO);
    jobInfo =
      (InputJobInfo) HCatUtil.deserialize(inputJobInfoStr);
    HCatSchema tableSchema = jobInfo.getTableInfo().getDataColumns();
    HCatSchema partitionSchema =
      jobInfo.getTableInfo().getPartitionColumns();
    hCatFullTableSchema = new HCatSchema(tableSchema.getFields());
    for (HCatFieldSchema hfs : partitionSchema.getFields()) {
      hCatFullTableSchema.append(hfs);
    }
    hCatSchemaFields = hCatFullTableSchema.getFields();
  }

  @Override
  public void map(LongWritable key, HCatRecord value,
    Context context)
    throws IOException, InterruptedException {
    context.write(convertToSqoopRecord(value), NullWritable.get());
  }

  private SqoopRecord convertToSqoopRecord(HCatRecord hcr)
    throws IOException {

    for (Map.Entry<Writable, Writable> e : colTypesJava.entrySet()) {
      String colName = e.getKey().toString();
      String javaColType = e.getValue().toString();
      int sqlType = ((IntWritable) colTypesSql.get(e.getKey())).get();
      HCatFieldSchema field =
        hCatFullTableSchema.get(colName.toLowerCase());
      HCatFieldSchema.Type fieldType = field.getType();
      Object hCatVal =
        hcr.get(colName.toLowerCase(), hCatFullTableSchema);
      String hCatTypeString = field.getTypeString();
      Object sqlVal = convertToSqoop(hCatVal, fieldType,
        javaColType, hCatTypeString);
      if (debugHCatExportMapper) {
        LOG.debug("hCatVal " + hCatVal + " of type "
          + (hCatVal == null ? null : hCatVal.getClass().getName())
          + ",sqlVal " + sqlVal + " of type "
          + (sqlVal == null ? null : sqlVal.getClass().getName())
          + ",java type " + javaColType + ", sql type = "
          + SqoopHCatUtilities.sqlTypeString(sqlType));
      }
      sqoopRecord.setField(colName, sqlVal);
    }
    return sqoopRecord;
  }

  private Object convertToSqoop(Object val,
    HCatFieldSchema.Type fieldType, String javaColType,
    String hCatTypeString) {

    if (val == null) {
      return null;
    }

    switch (fieldType) {
      case INT:
      case TINYINT:
      case SMALLINT:
      case FLOAT:
      case DOUBLE:
        val = convertToNumberTypes(val, javaColType);
        if (val != null) {
          return val;
        }
        break;
      case BOOLEAN:
        val = convertToBooleanTypes(val, javaColType);
        if (val != null) {
          return val;
        }
        break;
      case BIGINT:
        if (javaColType.equals(DATE_TYPE)) {
          return new Date((Long) val);
        } else if (javaColType.equals(TIME_TYPE)) {
          return new Time((Long) val);
        } else if (javaColType.equals(TIMESTAMP_TYPE)) {
          return new Timestamp((Long) val);
        } else {
          val = convertToNumberTypes(val, javaColType);
          if (val != null) {
            return val;
          }
        }
        break;
      case STRING:
        val = convertToStringTypes(val, javaColType);
        if (val != null) {
          return val;
        }
        break;
      case BINARY:
        val = convertToBinaryTypes(val, javaColType);
        if (val != null) {
          return val;
        }
        break;
      case ARRAY:
      case MAP:
      case STRUCT:
      default:
        throw new IllegalArgumentException("Cannot convert HCatalog type "
          + fieldType);
    }
    throw new IllegalArgumentException("Cannot convert HCatalog object of "
      + " type " + hCatTypeString + " to java object type "
      + javaColType);
  }

  private Object convertToBinaryTypes(Object val, String javaColType) {
    byte[] bb = (byte[]) val;
    if (javaColType.equals(BYTESWRITABLE)) {
      BytesWritable bw = new BytesWritable();
      bw.set(bb, 0, bb.length);
      return bw;
    }
    return null;
  }

  private Object convertToStringTypes(Object val, String javaColType) {
    String valStr = val.toString();
    if (javaColType.equals(BIG_DECIMAL_TYPE)) {
      return new BigDecimal(valStr);
    } else if (javaColType.equals(DATE_TYPE)) {
      return Date.valueOf(valStr);
    } else if (javaColType.equals(TIME_TYPE)) {
      return Time.valueOf(valStr);
    } else if (javaColType.equals(TIMESTAMP_TYPE)) {
      return Timestamp.valueOf(valStr);
    } else if (javaColType.equals(STRING_TYPE)) {
      return valStr;
    }
    return null;
  }

  private Object convertToBooleanTypes(Object val, String javaColType) {
    Boolean b = (Boolean) val;
    if (javaColType.equals(BOOLEAN_TYPE)) {
      return b;
    } else if (javaColType.equals(BYTE_TYPE)) {
      return (byte) (b ? 1 : 0);
    } else if (javaColType.equals(SHORT_TYPE)) {
      return (short) (b ? 1 : 0);
    } else if (javaColType.equals(INTEGER_TYPE)) {
      return (int) (b ? 1 : 0);
    } else if (javaColType.equals(LONG_TYPE)) {
      return (long) (b ? 1 : 0);
    } else if (javaColType.equals(FLOAT_TYPE)) {
      return (float) (b ? 1 : 0);
    } else if (javaColType.equals(DOUBLE_TYPE)) {
      return (double) (b ? 1 : 0);
    } else if (javaColType.equals(BIG_DECIMAL_TYPE)) {
      return new BigDecimal(b ? 1 : 0);
    }
    return null;
  }

  private Object convertToNumberTypes(Object val, String javaColType) {
    Number n = (Number) val;
    if (javaColType.equals(BYTE_TYPE)) {
      return n.byteValue();
    } else if (javaColType.equals(SHORT_TYPE)) {
      return n.shortValue();
    } else if (javaColType.equals(INTEGER_TYPE)) {
      return n.intValue();
    } else if (javaColType.equals(LONG_TYPE)) {
      return n.longValue();
    } else if (javaColType.equals(FLOAT_TYPE)) {
      return n.floatValue();
    } else if (javaColType.equals(DOUBLE_TYPE)) {
      return n.doubleValue();
    } else if (javaColType.equals(BIG_DECIMAL_TYPE)) {
      return new BigDecimal(n.doubleValue());
    } else if (javaColType.equals(BOOLEAN_TYPE)) {
      return n.byteValue() == 0 ? Boolean.FALSE : Boolean.TRUE;
    }
    return null;
  }

}