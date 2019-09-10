/*
 * Copyright (c) 2019, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.rapids.spark;

import ai.rapids.cudf.DType;
import ai.rapids.cudf.NvtxColor;
import ai.rapids.cudf.NvtxRange;
import ai.rapids.cudf.Scalar;
import ai.rapids.cudf.Table;
import ai.rapids.cudf.TimeUnit;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.execution.vectorized.WritableColumnVector;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarArray;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.apache.spark.sql.vectorized.ColumnarMap;
import org.apache.spark.unsafe.types.UTF8String;

import java.util.List;

/**
 * A GPU accelerated version of the Spark ColumnVector.
 * Most of the standard Spark APIs should never be called, as they assume that the data
 * is on the host, and we want to keep as much of the data on the device as possible.
 * We also provide GPU accelerated versions of the transitions to and from rows.
 */
public final class GpuColumnVector extends ColumnVector {

  public static final class GpuColumnarBatchBuilder implements AutoCloseable {
    private final ai.rapids.cudf.ColumnVector.Builder[] builders;
    private final StructField[] fields;

    /**
     * A collection of builders for building up columnar data.
     * @param schema the schema of the batch.
     * @param rows the maximum number of rows in this batch.
     * @param batch if this is going to copy a ColumnarBatch in a non GPU format that batch
     *              we are going to copy. If not this may be null. This is used to get an idea
     *              of how big to allocate buffers that do not necessarily correspond to the
     *              number of rows.
     */
    public GpuColumnarBatchBuilder(StructType schema, int rows, ColumnarBatch batch) {
      fields = schema.fields();
      int len = fields.length;
      builders = new ai.rapids.cudf.ColumnVector.Builder[len];
      boolean success = false;
      try {
        for (int i = 0; i < len; i++) {
          StructField field = fields[i];
          DType type = getRapidsType(field);
          TimeUnit units = getTimeUnits(field);
          if (type == DType.STRING) {
            // If we cannot know the exact size, assume the string is small and allocate
            // 8 bytes per row.  The buffer of the builder will grow as needed if it is
            // too small.
            int bufferSize = rows * 8;
            if (batch != null) {
              ColumnVector cv = batch.column(i);
              if (cv instanceof WritableColumnVector) {
                WritableColumnVector wcv = (WritableColumnVector)cv;
                if (!wcv.hasDictionary()) {
                  bufferSize = wcv.getArrayOffset(rows-1) +
                      wcv.getArrayLength(rows - 1);
                }
              }
            }
            builders[i] = ai.rapids.cudf.ColumnVector.builder(type, rows, bufferSize);
          } else {
            builders[i] = ai.rapids.cudf.ColumnVector.builder(type, units, rows);
          }
          success = true;
        }
      } finally {
        if (!success) {
          for (ai.rapids.cudf.ColumnVector.Builder b: builders) {
            if (b != null) {
              b.close();
            }
          }
        }
      }
    }

    public ai.rapids.cudf.ColumnVector.Builder builder(int i) {
      return builders[i];
    }

    public ColumnarBatch build(int rows) {
      ColumnVector[] vectors = new ColumnVector[builders.length];
      boolean success = false;
      try {
        for (int i = 0; i < builders.length; i++) {
          ai.rapids.cudf.ColumnVector cv = builders[i].build();
          vectors[i] = new GpuColumnVector(fields[i].dataType(), cv);
          cv.ensureOnDevice();
          builders[i] = null;
        }
        ColumnarBatch ret = new ColumnarBatch(vectors, rows);
        success = true;
        return ret;
      } finally {
        if (!success) {
          for (ColumnVector vec: vectors) {
            if (vec != null) {
              vec.close();
            }
          }
        }
      }
    }

    @Override
    public void close() {
      for (ai.rapids.cudf.ColumnVector.Builder b: builders) {
        if (b != null) {
          b.close();
        }
      }
    }
  }

  public static TimeUnit getTimeUnits(StructField field) {
    DataType type = field.dataType();
    return getTimeUnits(type);
  }

  public static TimeUnit getTimeUnits(DataType type) {
    if (type instanceof TimestampType) {
      return TimeUnit.MICROSECONDS;
    }
    return TimeUnit.NONE;
  }

  public static TimeUnit getTimeUnits(DType type) {
    if (type == DType.TIMESTAMP) {
      return TimeUnit.MICROSECONDS;
    }
    return TimeUnit.NONE;
  }

  private static DType toRapidsOrNull(DataType type) {
    if (type instanceof LongType) {
      return DType.INT64;
    } else if (type instanceof DoubleType) {
      return DType.FLOAT64;
    } else if (type instanceof ByteType) {
      return DType.INT8;
    } else if (type instanceof BooleanType) {
      return DType.BOOL8;
    } else if (type instanceof ShortType) {
      return DType.INT16;
    } else if (type instanceof IntegerType) {
      return DType.INT32;
    } else if (type instanceof FloatType) {
      return DType.FLOAT32;
    } else if (type instanceof DateType) {
      return DType.DATE32;
    } else if (type instanceof TimestampType) {
      return DType.TIMESTAMP;
    } else if (type instanceof StringType) {
      return DType.STRING; // TODO what do we want to do about STRING_CATEGORY???
    }
    return null;
  }

  public static boolean isSupportedType(DataType type) {
    return toRapidsOrNull(type) != null;
  }

  public static DType getRapidsType(StructField field) {
    DataType type = field.dataType();
    return getRapidsType(type);
  }

  public static DType getRapidsType(DataType type) {
    DType result = toRapidsOrNull(type);
    if (result == null) {
      throw new IllegalArgumentException(type + " is not supported for GPU processing yet.");
    }
    return result;
  }

  private static DataType getSparkType(DType type) {
    switch (type) {
      case BOOL8:
        return DataTypes.BooleanType;
      case INT8:
        return DataTypes.ByteType;
      case INT16:
        return DataTypes.ShortType;
      case INT32:
        return DataTypes.IntegerType;
      case INT64:
        return DataTypes.LongType;
      case FLOAT32:
        return DataTypes.FloatType;
      case FLOAT64:
        return DataTypes.DoubleType;
      case DATE32:
        return DataTypes.DateType;
      case TIMESTAMP:
        return DataTypes.TimestampType; // TODO need to verify that the TimeUnits are correct
      case STRING: //Fall through
      case STRING_CATEGORY:
        return DataTypes.StringType;
      default:
        throw new IllegalArgumentException(type + " is not supported by spark yet.");

    }
  }

  /**
   * Create an empty batch from the given format.  This should be used very sparingly because
   * returning an empty batch from an operator is almost always the wrong thing to do.
   */
  public static ColumnarBatch emptyBatch(List<Attribute> format) {
    StructType schema = new StructType();
    for (Attribute attribute: format) {
      schema = schema.add(new StructField(attribute.name(),
          attribute.dataType(),
          attribute.nullable(),
          null));
    }
    return new GpuColumnarBatchBuilder(schema, 0, null).build(0);
  }

  /**
   * Convert a ColumnarBatch to a table. The table will increment the reference count for all of
   * the columns in the batch, so you will need to close both the batch passed in and the table
   * returned to avoid any memory leaks.
   */
  public static Table from(ColumnarBatch batch) {
    return new Table(extractBases(batch));
  }

  /**
   * Convert a Table to a ColumnarBatch.  The columns in the table will have their reference counts
   * incremented so you will need to close both the table passed in and the batch returned to
   * not have any leaks.
   */
  public static ColumnarBatch from(Table table) {
    return from(table, 0, table.getNumberOfColumns());
  }

  /**
   * Get a ColumnarBatch from a set of columns in the Table. This gets the columns
   * starting at startColIndex and going until but not including untilColIndex. This will
   * increment the reference count for all columns converted so you will need to close
   * both the table that is passed in and the batch returned to be sure that there are no leaks.
   *
   * @param table  - a table of vectors
   * @param startColIndex - index of the first vector you want in the final ColumnarBatch
   * @param untilColIndex - until index of the columns. (ie doesn't include that column num)
   * @return       - a ColumnarBatch of the vectors from the table
   */
  public static ColumnarBatch from(Table table, int startColIndex, int untilColIndex) {
    int numColumns = untilColIndex - startColIndex;
    ColumnVector[] columns = new ColumnVector[numColumns];
    int finalLoc = 0;
    boolean success = false;
    try {
      for (int i = startColIndex; i < untilColIndex; i++) {
        columns[finalLoc] = from(table.getColumn(i).incRefCount());
        finalLoc++;
      }
      long rows = table.getRowCount();
      if (rows != (int) rows) {
        throw new IllegalStateException("Cannot support a batch larger that MAX INT rows");
      }
      ColumnarBatch ret = new ColumnarBatch(columns, (int)rows);
      success = true;
      return ret;
    } finally {
      if (!success) {
        for (ColumnVector cv: columns) {
          if (cv != null) {
            cv.close();
          }
        }
      }
    }
  }

  /**
   * Converts a cudf internal vector to a spark compatible vector. No reference counts
   * are incremented so you need to either close the returned value or the input value,
   * but not both.
   */
  public static GpuColumnVector from(ai.rapids.cudf.ColumnVector cudfCv) {
    return new GpuColumnVector(getSparkType(cudfCv.getType()), cudfCv);
  }

  public static GpuColumnVector from(Scalar scalar, int count) {
    return from(ai.rapids.cudf.ColumnVector.fromScalar(scalar, count));
  }

  /**
   * Get the underlying cudf columns from the batch.  This does not increment any
   * reference counts so if you want to use these columns after the batch is closed
   * you will need to do that on your own.
   */
  public static ai.rapids.cudf.ColumnVector[] extractBases(ColumnarBatch batch) {
    int numColumns = batch.numCols();
    ai.rapids.cudf.ColumnVector[] vectors = new ai.rapids.cudf.ColumnVector[numColumns];
    for (int i = 0; i < vectors.length; i++) {
      vectors[i] = ((GpuColumnVector)batch.column(i)).getBase();
    }
    return vectors;
  }

  /**
   * Get the underlying spark compatible columns from the batch.  This does not increment any
   * reference counts so if you want to use these columns after the batch is closed
   * you will need to do that on your own.
   */
  public static GpuColumnVector[] extractColumns(ColumnarBatch batch) {
    int numColumns = batch.numCols();
    GpuColumnVector[] vectors = new GpuColumnVector[numColumns];

    for (int i = 0; i < vectors.length; i++) {
      vectors[i] = ((GpuColumnVector)batch.column(i));
    }
    return vectors;
  }

  /**
   * Convert an entire batch to string categories if needed.  The resulting vectors will either be
   * new or have their reference counts incremented so to avoid leaks the input batch must be closed
   * when done and the output vectors must also be closed.
   */
  public static GpuColumnVector[] convertToStringCategoriesArrayIfNeeded(ColumnarBatch batch) {
    GpuColumnVector[] columns = extractColumns(batch);
    GpuColumnVector[] ret = new GpuColumnVector[columns.length];
    boolean success = false;
    try {
      for (int i = 0; i < columns.length; i++) {
        ret[i] = columns[i].convertToStringCategoriesIfNeeded();
      }
      success = true;
      return ret;
    } finally {
      if (!success) {
        for (GpuColumnVector c: ret) {
          if (c != null) {
            c.close();
          }
        }
      }
    }
  }

  /**
   * Convert an entire batch to string categories if needed.  The resulting vectors will either be
   * new or have their reference counts incremented so to avoid leaks the input batch must be closed
   * when done and the output batch must also be closed.
   */
  public static ColumnarBatch convertToStringCategoriesIfNeeded(ColumnarBatch batch) {
    return new ColumnarBatch(convertToStringCategoriesArrayIfNeeded(batch), batch.numRows());
  }

  private final ai.rapids.cudf.ColumnVector cudfCv;

  /**
   * Sets up the data type of this column vector.
   */
  private GpuColumnVector(DataType type, ai.rapids.cudf.ColumnVector cudfCv) {
    super(type);
    // TODO need some checks to be sure everything matches
    this.cudfCv = cudfCv;
  }

  public GpuColumnVector incRefCount() {
    // Just pass through the reference counting
    cudfCv.incRefCount();
    return this;
  }

  @Override
  public void close() {
    // Just pass through the reference counting
    cudfCv.close();
  }

  @Override
  public boolean hasNull() {
    return cudfCv.hasNulls();
  }

  @Override
  public int numNulls() {
    return (int) cudfCv.getNullCount();
  }

  @Override
  public boolean isNullAt(int rowId) {
    return cudfCv.isNull(rowId);
  }

  @Override
  public boolean getBoolean(int rowId) {
    return cudfCv.getBoolean(rowId);
  }

  @Override
  public byte getByte(int rowId) {
    return cudfCv.getByte(rowId);
  }

  @Override
  public short getShort(int rowId) {
    return cudfCv.getShort(rowId);
  }

  @Override
  public int getInt(int rowId) {
    return cudfCv.getInt(rowId);
  }

  @Override
  public long getLong(int rowId) {
    return cudfCv.getLong(rowId);
  }

  @Override
  public float getFloat(int rowId) {
    return cudfCv.getFloat(rowId);
  }

  @Override
  public double getDouble(int rowId) {
    return cudfCv.getDouble(rowId);
  }

  @Override
  public ColumnarArray getArray(int rowId) {
    throw new IllegalStateException("Arrays are currently not supported by rapids cudf");
  }

  @Override
  public ColumnarMap getMap(int ordinal) {
    throw new IllegalStateException("Maps are currently not supported by rapids cudf");
  }

  @Override
  public Decimal getDecimal(int rowId, int precision, int scale) {
    throw new IllegalStateException("The decimal type is currently not supported by rapids cudf");
  }

  @Override
  public UTF8String getUTF8String(int rowId) {
    // TODO need a cheaper way to go directly to the String
    return UTF8String.fromString(cudfCv.getJavaString(rowId));
  }

  @Override
  public byte[] getBinary(int rowId) {
    throw new IllegalStateException("Binary data access is currently not supported by rapids cudf");
  }

  @Override
  public ColumnVector getChild(int ordinal) {
    throw new IllegalStateException("Struct and struct like types are currently not supported by rapids cudf");
  }

  public GpuColumnVector convertToStringCategoriesIfNeeded() {
    if (cudfCv.getType() == DType.STRING) {
      try (NvtxRange nvtxRange = new NvtxRange("to string category", NvtxColor.RED)) {
        return from(cudfCv.asStringCategories());
      }
    } else {
      return this.incRefCount();
    }
  }

  public ai.rapids.cudf.ColumnVector getBase() {
    return cudfCv;
  }

  public long getRowCount() { return cudfCv.getRowCount(); }
}