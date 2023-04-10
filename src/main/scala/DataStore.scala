package com.databricks.labs.geospatial.searches

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

import org.apache.spark.rdd.RDD 
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.sql.functions.col

import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}

trait DataStore{
  def search(rdd: RDD[SearchInquery]): RDD[SearchResult]
  def recordCount: Long

  def toInqueryRDD(df: DataFrame, radius: Integer, maxResults: Integer=10, metric: String = "Miles"): RDD[SearchInquery] = {
    val measurement = metric match {
      case s if s.toLowerCase.startsWith("k") => Measurement.Kilometers
      case s if s.toLowerCase.startsWith("m") => Measurement.Miles
      case _ => throw new Exception("Error: Unrecognized metric of measurement: " + metric)
    }

    df.select(col("id").cast(StringType), col("latitude").cast(DoubleType), col("longitude").cast(DoubleType)).rdd.map(row =>
      {
        val g = new GeoRecord(row.getAs("id"), row.getAs("latitude"), row.getAs("longitude"))
        new SearchInquery(g, radius, maxResults, measurement)
      })
  }
}


/*
 * SparkDS to represent functions to query a search space
 *
 */
object SparkDS {
  def fromDF(df: DataFrame)(implicit spark: SparkSession): DataStore = {
    import spark.implicits._
    new SparkDS(df.select(col("id").cast(StringType), col("latitude").cast(DoubleType), col("longitude").cast(DoubleType)).rdd.map(row =>
      {
        val g = new GeoRecord(row.getAs("id"), row.getAs("latitude"), row.getAs("longitude"))
          (g.getKey, g.getValue)
      }
    ).toDF("key", "value"))
  }

  val ERROR = 9999
  val distanceKm = (pointA: String, pointB: String) => {
    (io.circe.parser.decode[GeoRecord](pointA), io.circe.parser.decode[GeoRecord](pointB)) match {
      case (Right(a), Right(b)) => a.distanceKM(b)
      case (_, _) => ERROR
    }
  }
  val distanceKmUDF = udf(distanceKm)
}

class SparkDS(df: DataFrame) extends DataStore{
  override def search(rdd: RDD[SearchInquery]): RDD[SearchResult] = ???
  override def search(inquire: SearchInquery): SearchResult = {
    val searchSpace = GeoSearch.getSearchSpaceGeohash(inquire.rec.latitude, inquire.rec.longitude, inquire.radius, inquire.ms)
    val searchDistanceKM = GeoSearch.sizeAsKM(inquire.radius, inquire.ms)

    val start = System.nanoTime()
    var arr = Array[SearchResultValue]()
    val furthestPoint = Option(None)

    val searchResults = df.filter(col("key").like(searchSpace + "%")).filter( SparkDS.distanceKmUDF(col("value"), lit(inquire.rec.getValue)) < searchDistanceKM ).select(col("value")).collect()

    arr = searchResults
      .map(row => io.circe.parser.decode[GeoRecord](row.mkString))
      .map(rec => {
        rec match {
          case Left(e) => throw new Exception("Error: Unable to decode a record " + e)
          case Right(v) =>  SearchResultValue(v, SparkDS.distanceKm(v.getValue,inquire.rec.getValue), Measurement.Km)
        }
      })

    new SearchResult(arr.length, arr, searchSpace, (System.nanoTime - start).toDouble / 1000000000) //convert to seconds
  }
  override def recordCount = df.count()
}
