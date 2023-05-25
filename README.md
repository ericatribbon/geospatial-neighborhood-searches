![image](https://user-images.githubusercontent.com/86326159/206014015-a70e3581-e15c-4a10-95ef-36fd5a560717.png)

# Scaling Geospatial Nearest Neighbor Searches with Spark Serverless SQL

## Problem Statement

Determining nearby locations to a point on a map becomes difficult as datasets grow larger. For "small" datasets a direct comparison of distance can be used but does not scale as your data grows. To scale, a technique called [geohashing](https://en.wikipedia.org/wiki/Geohash) is used but is not accurate due to how points get compared within an area. 

## An Accurate Scalable Solution

This repo provides a solution that provides accuracy and scale using Spark's distributed data processing as well as high performant caching. In this repo we demonstrate how you can use Spark's Serverless SQL for a high performant cache or a cloud's NoSQL (the example provided here is using CosmosDB). This can be extended to other NoSQLs like BigTable, DynamoDB, and MongoDB. 

### Specifying a radius returns all points contained inside the associated point of origin.  

![image](./img/upmc_childrens_hospital.png?raw=true)

### And given many points of origin, all associated values are returned for each origin of interest.

![image](./img/many_locations.png?raw=true)

## Getting started

### Installation for using Spark Serverless as a data cache

1. Attach jar from repo as a cluster library
2. Download and attach the [Spark JDBC Driver](https://ohdsi.github.io/DatabaseConnectorJars/) as a cluster library

### Input 

Given two tables with identifcal columns (id:STRING, latitude:DOUBLE, longitude:DOUBLE), perform a geospatial search of all points within the specified radius 

### Output Data Dictionary

|Column|Description|
|---|---|
|origin.id|Origin table's ID column|
|origin.latitude|Origin table's latitude coordinate|
|origin.longitude|Origin table's longitude coordinate|
|neighbors|Array of matching results. Pivot to rows using explode() function|
|neighbors.value.id|Surrounding neighbor's ID column|
|neighbors.value.latitude|Surrounding neighbor's latitude coordinate|
|neighbors.value.longitude|Surrounding neighbor's longitude coordinate|
|neighbors.euclideanDistance|Distance between origin point and neighbor. The Unit is either Km or Mi matching the input specified|
|neighbors.ms|The unit of measurement for euclideanDistance (miles or kilometers)|
|searchSpace|The geohash searched. Larger string lengths === smaller search spaces (faster) and vice versa holds true|
|searchTimerSeconds|The number of seconds it took to find all neighbors for the origin point|

### Running the search algorithm
``` scala
import com.databricks.industry.solutions.geospatial.searches._ 
implicit val spark2 = spark 

//Configurations
//For generating your auth token in your JDBC URL connection, see https://docs.databricks.com/dev-tools/auth.html#pat
val jdbcUrl = "jdbc:spark://eastus2.azuredatabricks.net:443/default;transportMode=http;ssl=1;httpPath=sql/protocolv1/o/5206439413157315/0812-164905-tear862;AuthMech=3;UID=token;PWD=XXXX" 
val tempTable = "geospatial_searches.va_facilities_temp" 

//Search input Params
val radius=5
val maxResults = 100
val measurementType="miles"
```


## Project support 

Please note the code in this project is provided for your exploration only, and are not formally supported by Databricks with Service Level Agreements (SLAs). They are provided AS-IS and we do not make any guarantees of any kind. Please do not submit a support ticket relating to any issues arising from the use of these projects. The source in this project is provided subject to the Databricks [License](./LICENSE). All included or referenced third party libraries are subject to the licenses set forth below.

Any issues discovered through the use of this project should be filed as GitHub Issues on the Repo. They will be reviewed as time permits, but there are no formal SLAs for support. 
___

Open Source libraries used in this project 

| library                                | description             | license    | source                                              | coordinates |
|----------------------------------------|-------------------------|------------|-----------------------------------------------------|------------------ |
| Geohashes  | Converting Lat/Long to a Geohash      | Apache 2.0       | https://github.com/kungfoo/geohash-java                      |  ch.hsr:geohash:1.4.0 |
___

&copy; 2022 Databricks, Inc. All rights reserved. The source in this notebook is provided subject to the Databricks License [https://databricks.com/db-license-source].  All included or referenced third party libraries are subject to the licenses set forth below.


## Alternatives to Spark Serverless data cache: Cloud NoSQL 

Included in this repo is an example imlpementation of using Azure's CosmosDB as a data cache. Other NoSQLs can be supported by implementing the DataStore trait. 

| library                                | description             | license    | source                                              | coordinates |
|----------------------------------------|-------------------------|------------|-----------------------------------------------------|------------------ |
| Spark + Azure Cosmos | Connecting DataFrames to CosmosDB | MIT | https://github.com/Azure/azure-cosmosdb-spark | com.azure.cosmos.spark:azure-cosmos-spark_3-2_2-12:4.11.2 |
| Azure Cosmos Client | Connectivity to CosmosDB | MIT | https://github.com/Azure/azure-cosmosdb-java | com.azure:azure-cosmos:4.39.0 | 


### e.g. Running on CosmosDB libraries

1. Attach jar from repo as a cluster library
2. Install Azure/Databricks related libraries above using Maven coordinates
3. Create An Azure NoSQL Container for document storage 
  (Recommended setting index policy on Cosmos)
``` json
{
    "indexingMode": "consistent",
    "automatic": true,
    "includedPaths": [],
    "excludedPaths": [
        {
            "path": "/*"
        },
        {
            "path": "/\"_etag\"/?"
        }
    ]
}
```

### Running setup configurations

``` scala
import com.azure.cosmos.spark._, com.azure.cosmos.spark.CosmosConfig, com.azure.cosmos.spark.CosmosAccountConfig, com.databricks.industry.solutions.geospatial.searches._
implicit val spark2 = spark

// Provide Connection Details, replace the below settings
val cosmosEndpoint = "https://XXXX.documents.azure.com:443/"
val cosmosMasterKey = "..."
val cosmosDatabaseName = "GeospatialSearches"
val cosmosContainerName = "GeoMyTable"

// Configure NoSQL Connection Params
//https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/cosmos/azure-cosmos-spark_3_2-12/docs/migration.md
implicit val cfg = Map("spark.cosmos.accountEndpoint" -> cosmosEndpoint,
  "spark.cosmos.accountKey" -> cosmosMasterKey,
  "spark.cosmos.database" -> cosmosDatabaseName,
  "spark.cosmos.container" -> cosmosContainerName,
  "spark.cosmos.write.bulk.enabled" -> "true",     
  "spark.cosmos.write.strategy" -> "ItemOverwrite"
)

//Populate the NoSQL DataStore from the first dataset (VA Hospital location dataset)
val ds = CosmosDS.fromDF(spark.table(searchTable), cfg)

//Set radius to search for
val radius=25
val maxResults = 5
val measurementType="miles"

//Set the tables for search (same table in this case)
val targetTable = "geospatial_searches.sample_facilities"
val searchTable = "geospatial_searches.sample_facilities"

//Secondary dataset search (We're using same dataset for both tables)
val searchRDD = ds.toInqueryRDD(spark.sql(targetTable), radius, maxResults, measurementType)

//Perform search and save results
val resultRDD = ds.asInstanceOf[CosmosDS].search(searchRDD)
ds.fromSearchResultRDD(resultRDD).write.mode("overwrite").saveAsTable("geospatial_searches.sample_results")
```

### Performance on **Sparse Dataset comparison**

``` scala
//Avg 0.3810 seconds per request
spark.sql("select avg(searchTimerSeconds) from ...")

//Median 0.086441679 seconds per request
spark.table("...").select("searchTimerSeconds")
        .stat
        .approxQuantile("searchTimerSeconds", Array(0.5), 0.001) //median
        .head

//75th percentile 0.528239604 seconds per request
spark.table("...").select("searchTimerSeconds")
        .stat
        .approxQuantile("searchTimerSeconds", Array(0.75), 0.001) 
        .head
```

