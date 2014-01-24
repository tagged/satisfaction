package com.klout
package satisfaction
package hadoop
package hive

import org.joda.time.format.DateTimeFormat
import org.joda.time.Days
import org.joda.time.DateTime
import scala.io.Source

// class HiveSatisfier(ms: MetaStore) extends Satisfier with DataProducing {

//     override def satisfy(goal: Goal, witness: Witness) {
//         if (!goal.isInstanceOf[HiveGoal])
//             throw new IllegalArgumentException("Only HiveGoals are supported")
//     }

// }

// object HiveSatisfier extends HiveSatisfier(MetaStore)

///case class HiveSatisfier(queryTemplate: String, driver: HiveClient) extends Satisfier {
case class HiveSatisfier(queryResource: String, driver: HiveDriver) extends Satisfier with TrackOriented with MetricsProducing {

    def executeMultiple(hql: String): Boolean = {
        val multipleQueries = hql.split(";")
        multipleQueries.foreach(query => {
            if (query.trim.length > 0) {
                println(s" Executing query $query")
                val results = driver.executeQuery(query)
                if (!results)
                    return results

            }

        })
        true
    }
    
    
    /// XXX Set with implicits !!!
    override def setTrack( tr : Track ) = {
      super.setTrack( tr)
      if(driver.isInstanceOf[TrackOriented]) {
         val trackCast = driver.asInstanceOf[TrackOriented]
         trackCast.setTrack(tr)
      }
    }
    
    def queryTemplate : String = {
       println(" Query Template -- query Resource is " + queryResource)      
       if( queryResource.endsWith(".hql"))  { 
          track.getResource( queryResource) 
       } else {
         queryResource
       }
    }
    
    def loadSetup = {
      try {
        val setupScript = track.getResource("setup.hql")
        if( setupScript != null) {
          println(s" Running setup script $setupScript")
          executeMultiple( setupScript)
        }
        
      } catch { 
        case ill : IllegalArgumentException =>
          println("Unable to find setup.hql")
        case unexpected:Throwable  =>
         throw unexpected 
      }
      
    }
    

    @Override
    override def satisfy(params: Substitution): ExecutionResult = {
      val execResult = new ExecutionResult( queryTemplate, new DateTime)
      try {

        val allProps = getTrackProperties(params)
        println(s" Track Properties is $allProps ")
        val queryMatch = Substituter.substitute(queryTemplate, allProps) match {
            case Left(badVars) =>
                println(" Missing variables in query Template ")
                badVars.foreach { s => println("  ## " + s) }
                execResult.markFailure
                execResult.errorMessage = "Missing variables in queryTemplate " + badVars.mkString(",")
            case Right(query) =>
                val startTime = new DateTime
                try {
                    loadSetup
                    println(" Beginning executing Hive queries ..")
                    driver.useDatabase("bi_maxwell")
                    val result=  executeMultiple(query)
                    execResult.metrics.mergeMetrics( jobMetrics)
                    if( result ) { execResult.markSuccess } else { execResult.markFailure }
                } catch {
                    case unexpected : Throwable =>
                        println(s" Unexpected error $unexpected")
                        unexpected.printStackTrace()
                        val execResult = new ExecutionResult(query, startTime)
                        execResult.markUnexpected(unexpected)
                }
        }
        return execResult 
      } catch { 
        case unexpected : Throwable =>
          System.out.println(" Unexpected !!! "+ unexpected)
          unexpected.printStackTrace( System.out)
          unexpected.printStackTrace( System.err)
          execResult.markUnexpected( unexpected)
      }
    }
    
   ///
    def jobMetrics : MetricsCollection =  {
       if( driver.isInstanceOf[MetricsProducing])  {
           val mpDriver = driver.asInstanceOf[MetricsProducing] 
            mpDriver.jobMetrics
       } else {
         new MetricsCollection("Hive Query")
       }
    }
   
    
   
}

object HiveSatisfier {
  
}
