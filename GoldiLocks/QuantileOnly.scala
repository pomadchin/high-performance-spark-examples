

import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.runtime.{universe => ru}

class QuantileWithHashMap(val valPairs: RDD[((Double, Int), Long)], val colIndexList: List[Int], targetRanks: List[Long]) {
  /*
   * n is value of the last column index in the valPairs. It represents the width of the part of the dataset
   * that we care about. It is possible that n will be greater than the number
   * of columns if some columns between 0 and n are not included
   */
  val n = colIndexList.last+1

  /**
   * @return A map of colIndex -> Array of rank stats for column indices (corresponding to the class param)
   */
  def findQuantiles( ) = {
    val sorted = valPairs.sortByKey()
    sorted.persist(StorageLevel.MEMORY_AND_DISK)
    val parts : Array[Partition] = sorted.partitions
    val map1 = getTotalsForeachPart(sorted, parts.length)
    val map2  = getLocationsOfRanksWithinEachPart(map1)
    val result = findElementsIteratively(sorted, map2)
    result.groupByKey().collectAsMap()
  }


  def findQuantilesWithCustomStorage(storageLevel: StorageLevel, checkPoint : Boolean, directory : String = "") = {
    val sorted  = valPairs.sortByKey()
    if(storageLevel!=StorageLevel.NONE){
      sorted.persist(storageLevel)
    }
    if(checkPoint){
      sorted.sparkContext.setCheckpointDir(directory)
      if(storageLevel.equals(StorageLevel.NONE)){
        println("Warning: Checkpointing without storing can be very slow. Consider changing Storage level Param ")
      }
      sorted.checkpoint()
    }
    val parts : Array[Partition] = sorted.partitions
    val map1 = getTotalsForeachPart(sorted, parts.length)
    val map2  = getLocationsOfRanksWithinEachPart(map1)
    val result = findElementsIteratively(sorted, map2)
    result.groupByKey().collectAsMap()
  }

  /**
   * @param sorted
   * @param numPartitions
   * @return an RDD the length of the number of partitions, where each row is a triple (partition Index
   */
  def getTotalsForeachPart(sorted: RDD[((Double, Int), Long)], numPartitions: Int) = {
    val zero = Array.fill[Long](n)(0)
    sorted.mapPartitionsWithIndex((index : Int, it : Iterator[((Double, Int), Long)]) => {
      val keyPair : Array[Long] = it.aggregate(zero)(
        (a : Array[Long], v : ((Double ,Int), Long)) => {
          val ((value, colIndex) , count) = v
          a(colIndex) = a(colIndex) + count
          a},
        (a : Array[Long], b : Array[Long]) => {
          require(a.length == b.length)
          a.zip(b).map{ case(aVal, bVal) => aVal + bVal}
        })
      Iterator((index, keyPair))
    }).collect()
  }
  /**
   * @param partitionMap- the result of the previous method
   * @return and Array, locations where locations(i) = (i, list of each (colIndex, Value)
   *         in that partition value pairs that correspond to one of the target rank statistics for that col
   */
  def getLocationsOfRanksWithinEachPart(partitionMap : Array[(Int, Array[Long])]) : Array[(Int, List[(Int, Long)])]  = {
    val runningTotal = Array.fill[Long](n)(0)
    partitionMap.sortBy(_._1).map { case (partitionIndex, totals)=> {
      val relevantIndexList = new  scala.collection.mutable.MutableList[(Int, Long)]()
      totals.zipWithIndex.foreach{ case (colCount, colIndex)  => {
        val runningTotalCol = runningTotal(colIndex)
        runningTotal(colIndex) += colCount
        val ranksHere = targetRanks.filter(rank =>
          (runningTotalCol <= rank && runningTotalCol + colCount >= rank)
        )
        ranksHere.foreach(rank => {
          relevantIndexList += ((colIndex, rank-runningTotalCol))
        })
      }} //end of mapping col counts
      (partitionIndex, relevantIndexList.toList)
    }}
  }

  /**
   * @param sorted
   * @param locations
   * @return An iterator of columnIndex, value pairs which correspond only to the values at which are
   *         rank statistics.
   */
  def findElementsIteratively(sorted : RDD[((Double, Int), Long)], locations : Array[(Int, List[(Int, Long)])]) = {
    sorted.mapPartitionsWithIndex((index : Int, it : Iterator[((Double, Int), Long)]) => {
      val targetsInThisPart = locations(index)._2
      val len = targetsInThisPart.length
      if(len >0 ) {
        val newIt = PartitionProcessingUtil2.getNewValues(it, targetsInThisPart)
        newIt}
      else Iterator.empty
    } )
  }
}

object PartitionProcessingUtil2 extends Serializable {

  def getNewValues(it: Iterator[((Double, Int), Long)], targetsInThisPart: List[(Int, Long)]): Iterator[(Int, Double)] = {
    val partMap = targetsInThisPart.groupBy(_._1).mapValues(_.map(_._2))
    val keysInThisPart = targetsInThisPart.map(_._1).distinct
    val runningTotals: mutable.HashMap[Int, Long] = new mutable.HashMap()
    keysInThisPart.foreach(key => runningTotals += ((key, 0L)))
    val newIt: ArrayBuffer[(Int, Double)] = new scala.collection.mutable.ArrayBuffer()
    it.foreach { case ((value, colIndex), count) => {
      if (keysInThisPart.contains(colIndex) ) {
        val total = runningTotals(colIndex)
        val ranksPresent =  partMap(colIndex).filter(v => (v <= count + total) && (v > total))
        ranksPresent.foreach(r => {
          newIt += ((colIndex, value))
        })
        runningTotals.update(colIndex, total + count)
      }
    }}
    newIt.toIterator
  }
}
