package com.yotpo.metorikku.output.writers.file
import com.yotpo.metorikku.configuration.job.output.Hudi
import com.yotpo.metorikku.output.Writer
import org.apache.log4j.LogManager
import org.apache.spark.sql.types.{DataType, StructField, StructType}
import org.apache.spark.sql.functions.{col, lit, when, max}
import org.apache.spark.sql._

// REQUIRED: -Dspark.serializer=org.apache.spark.serializer.KryoSerializer
// http://hudi.incubator.apache.org/configurations.html

class HudiOutputWriter(props: Map[String, Object], hudiOutput: Option[Hudi]) extends Writer {
  val log = LogManager.getLogger(this.getClass)

  case class HudiOutputProperties(path: Option[String],
                                  saveMode: Option[String],
                                  keyColumn: Option[String],
                                  timeColumn: Option[String],
                                  partitionBy: Option[String],
                                  tableName: Option[String],
                                  hivePartitions: Option[String],
                                  extraOptions: Option[Map[String, String]],
                                  alignToPreviousSchema: Option[Boolean],
                                  supportNullableFields: Option[Boolean])

  val hudiOutputProperties = HudiOutputProperties(
    props.get("path").asInstanceOf[Option[String]],
    props.get("saveMode").asInstanceOf[Option[String]],
    props.get("keyColumn").asInstanceOf[Option[String]],
    props.get("timeColumn").asInstanceOf[Option[String]],
    props.get("partitionBy").asInstanceOf[Option[String]],
    props.get("tableName").asInstanceOf[Option[String]],
    props.get("hivePartitions").asInstanceOf[Option[String]],
    props.get("extraOptions").asInstanceOf[Option[Map[String, String]]],
    props.get("alignToPreviousSchema").asInstanceOf[Option[Boolean]],
    props.get("supportNullableFields").asInstanceOf[Option[Boolean]])


  // scalastyle:off cyclomatic.complexity
  // scalastyle:off method.length
  override def write(dataFrame: DataFrame): Unit = {
    if (dataFrame.head(1).isEmpty) {
      log.info("Skipping writing to hudi on empty dataframe")
      return
    }
    log.info(s"Starting to write dataframe to hudi")
    var df = dataFrame
    // To support schema evolution all fields should be nullable
    df = this.hudiOutputProperties.supportNullableFields match {
      case Some(true) => supportNullableFields(df)
      case _ => df
    }

    df = this.hudiOutputProperties.alignToPreviousSchema match {
      case Some(true) => alignToPreviousSchema(df)
      case _ => df
    }

    val writer = df.write

    writer.format("org.apache.hudi")

    // Handle hudi job configuration
    hudiOutput match {
      case Some(config) => {
        updateJobConfig(config, writer)
      }
      case None =>
    }

    // Handle path
    val path: Option[String] = (hudiOutputProperties.path, hudiOutput) match {
      case (Some(path), Some(output)) => Option(output.dir + "/" + path)
      case (Some(path), None) => Option(path)
      case _ => None
    }
    path match {
      case Some(filePath) => writer.option("path", filePath)
      case None =>
    }

    // Mandatory
    writer.option("hoodie.datasource.write.recordkey.field",  hudiOutputProperties.keyColumn.get)
    writer.option("hoodie.datasource.write.precombine.field", hudiOutputProperties.timeColumn.get)

    //Hard Delete
    writer.option("hoodie.datasource.write.payload.class", classOf[OverwriteWithLatestAvroPayloadWithDelete].getName)

    hudiOutputProperties.saveMode match {
      case Some(saveMode) => writer.mode(saveMode)
      case None => writer.mode(SaveMode.Append)
    }

    hudiOutputProperties.tableName match {
      case Some(tableName) => {
        writer.option("hoodie.table.name", tableName)
        writer.option("hoodie.datasource.hive_sync.table", tableName)
      }
      case None =>
    }

    hudiOutputProperties.partitionBy match {
      case Some(partitionBy) => {
        writer.option("hoodie.datasource.write.partitionpath.field", partitionBy)
        writer.option("hoodie.datasource.write.keygenerator.class", "org.apache.hudi.SimpleKeyGenerator")
      }
      case None => writer.option("hoodie.datasource.write.keygenerator.class", "org.apache.hudi.NonpartitionedKeyGenerator")
    }

    hudiOutputProperties.hivePartitions match {
      case Some(hivePartitions) => {
        writer.option("hoodie.datasource.hive_sync.partition_fields", hivePartitions)
        writer.option("hoodie.datasource.hive_sync.partition_extractor_class", "org.apache.hudi.hive.MultiPartKeysValueExtractor")
      }
      case None =>
    }

    hudiOutputProperties.extraOptions match {
      case Some(extraOptions) => writer.options(extraOptions)
      case None =>
    }

    writer.save()
  }

  private def updateJobConfig(config: Hudi, writer: DataFrameWriter[_]): Unit = {
    config.parallelism match {
      case Some(parallelism) => {
        writer.option("hoodie.insert.shuffle.parallelism", parallelism)
        writer.option("hoodie.bulkinsert.shuffle.parallelism", parallelism)
        writer.option("hoodie.upsert.shuffle.parallelism", parallelism)
      }
      case None =>
    }
    config.maxFileSize match {
      case Some(maxFileSize) => writer.option("hoodie.parquet.max.file.size", maxFileSize)
      case None =>
    }
    config.storageType match {
      case Some(storageType) => writer.option("hoodie.datasource.write.storage.type", storageType) // MERGE_ON_READ/COPY_ON_WRITE
      case None =>
    }
    config.operation match {
      case Some(operation) => writer.option("hoodie.datasource.write.operation", operation) // bulkinsert/upsert/insert
      case None =>
    }
    config.maxVersions match {
      case Some(maxVersions) => {
        writer.option("hoodie.cleaner.fileversions.retained", maxVersions)
        writer.option("hoodie.cleaner.policy", "KEEP_LATEST_FILE_VERSIONS")
      }
      case None =>
    }
    config.hiveJDBCURL match {
      case Some(hiveJDBCURL) => {
        writer.option("hoodie.datasource.hive_sync.jdbcurl", hiveJDBCURL)
        writer.option("hoodie.datasource.hive_sync.enable", "true")
      }
      case None =>
    }
    config.hiveDB match {
      case Some(hiveDB) => writer.option("hoodie.datasource.hive_sync.database", hiveDB)
      case None =>
    }
    config.hiveUserName match {
      case Some(hiveUserName) => writer.option("hoodie.datasource.hive_sync.username" , hiveUserName)
      case None =>
    }
    config.hivePassword match {
      case Some(hivePassword) => writer.option("hoodie.datasource.hive_sync.password" , hivePassword)
      case None =>
    }
    config.options match {
      case Some(options) => writer.options(options)
      case None =>
    }
  }


  private def alignToPreviousSchema(dataFrame: DataFrame): DataFrame = {
    var df = dataFrame
    val ss = dataFrame.sparkSession

    // If table existed before, get its schema
    val previousSchema: Option[StructType] = this.hudiOutputProperties.tableName match {
      case Some(tableName) => {
        ss.catalog.tableExists(tableName) match {
          case true => Option(ss.table(tableName).schema)
          case false => None
        }
      }
      case None => None
    }
    // By default we will add missing columns according to previous schema, if exists
    df = alignToSchemaColumns(df, previousSchema)

    // // By default we will remove completely null columns (will return them if existed in previous schemas), you can disable this behaviour
    removeNullColumns(df, previousSchema)

  }

  def supportNullableFields(dataFrame: DataFrame): DataFrame = {
    val schema = StructType(dataFrame.schema.fields.map(
      field => field.copy(nullable = true))
    )
    dataFrame.sparkSession.createDataFrame(dataFrame.rdd, schema)
  }

  def alignToSchemaColumns(df: DataFrame, previousSchema: Option[StructType]) : DataFrame = {
    val lowerCasedColumns = df.columns.map(f => f.toLowerCase)
    previousSchema match {
        case Some(sch) => {
          // scalastyle:off null
          val missingColumns = sch.fields.filter(f => !f.name.startsWith("_hoodie") &&
            !lowerCasedColumns.contains(f.name.toLowerCase)).map(f => lit(null).as(f.name))
          // scalastyle:on null
          df.select(col("*") +: missingColumns :_*)
          }
          case None => df
        }
    }

  def removeNullColumns(dataFrame: DataFrame, previousSchema: Option[StructType]): DataFrame = {
    var df = dataFrame
    val nullColumns = df
      .select(df.schema.fields.map(
        f =>
          when(
            max(col(f.name)).isNull, true)
            .otherwise(false)):_*)
      .collect()(0)

    var fieldMap = Map[String, DataType]()

    val schema = StructType(df.schema.fields.zipWithIndex.flatMap(
      a => {
        val field = a._1.copy(nullable = true)
        val index = a._2

        // Add nullability, not on by default
        val fieldName = field.name
        var returnedFields = List[StructField]()

        // Column is detected as having only null values, we need to remove it
        nullColumns(index).asInstanceOf[Boolean] match {
            case true => {
              df = df.drop(fieldName)

              // Check if removed column existed in a previous schema, if so, use the previous schema definition
              previousSchema match {
                case Some(sch) => {
                  sch.fields.filter(f => f.name.toLowerCase == fieldName.toLowerCase).foreach(f => fieldMap += (fieldName -> f.dataType))
                }
                case None =>
              }
            }
            case false => returnedFields = returnedFields :+ field
          }

        returnedFields
      })
    )

    // Update the actual schema
    df = df.sparkSession.createDataFrame(df.rdd, schema)

    // Add the new columns as null columns
    for ((name, dataType) <- fieldMap) {
      // scalastyle:off null
      df = df.withColumn(name, lit(null).cast(dataType))
      // scalastyle:on null
    }
    df
  }


   }
// scalastyle:on cyclomatic.complexity
// scalastyle:on method.length

