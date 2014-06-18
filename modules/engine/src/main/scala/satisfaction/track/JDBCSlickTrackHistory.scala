package com.klout
package satisfaction
package track

import org.joda.time._
import engine.actors.GoalStatus
import engine.actors.GoalState
import scala.slick.driver.H2Driver.simple._
import scala.slick.jdbc.{GetResult, StaticQuery => Q}
import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.lifted.ProvenShape
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import Q.interpolation
import scala.slick.jdbc.meta.MTable
import java.sql.Timestamp
import java.util.Date


/**
 * Using slick with H2 as our light-weight db
 * TODO:
 *  - figure out syntax for updating GoalRun.runId in map
 *  - update dummyWitness<->String functions when they are ready
 */

class JDBCSlickTrackHistory  extends TrackHistory{
	/**
	 * class for database formatting
	 */
	class TrackHistoryTable (tag: Tag) extends Table[(Int, String, String, String, String, String, String, Timestamp, Option[Timestamp], String)](tag, "TrackHistoryTable") {
  		  def id : Column[Int]= column[Int]("id", O.PrimaryKey, O.AutoInc)
		  def trackName : Column[String] = column[String]("trackName")
		  def forUser: Column[String] = column[String]("forUser")
		  def version: Column[String] = column[String]("version")
		  def variant: Column[String] = column[String]("variant")
		  def goalName: Column[String] = column[String]("goalName")
		  def witness: Column[String] = column[String]("witness")
		  def startTime: Column[Timestamp] = column[Timestamp]("startTime")
		  def endTime: Column[Option[Timestamp]] = column[Option[Timestamp]]("endTime", O.Nullable)
		  def state: Column[String] = column[String]("state")
		  
		  def * : ProvenShape[(Int, String, String, String, String, String, String, Timestamp, Option[Timestamp], String)] = (id, trackName, forUser, version, variant, goalName, witness, startTime, endTime, state)
		}
	
	/**
	 * Encapsulate DB drivers/info
	 *  MOVE this as a external class
	 *  	it is left this way due to a db connection error (wrong userpass)
	 */
	object driverInfo {
	  val JDBC_DRIVER : String =  "org.h2.Driver"
	  val DB_URL : String = "jdbc:h2:file:data/sample" //change this to a file url, for persistence!
	  val USER : String = "sa"
	  val PASS : String = ""
	  val mainTable : String = "TrackHistoryTable"

	  val table : TableQuery[TrackHistoryTable] = TableQuery[TrackHistoryTable]
	  var db = Database.forURL(DB_URL, driver = JDBC_DRIVER) 
	  db withSession {
	    implicit Session =>
	      if (MTable.getTables("TrackHistoryTable").list().isEmpty) {
	    	 table.ddl.create
	      }
	  }
	} // object H2Driverinfo


	override def startRun(trackDesc : TrackDescriptor, goalName: String, witness: Witness, startTime: DateTime) : String =   {
	  var insertedID = -1
	 driverInfo.db withSession {
	   implicit session =>
		insertedID = (driverInfo.table returning driverInfo.table.map(_.id)) += (1, trackDesc.trackName, trackDesc.forUser, trackDesc.version, trackDesc.variant.toString(), 
																					goalName, "dummyWitness", new Timestamp(startTime.getMillis()), None, 
																					GoalState.Running.toString())
	 }
	  insertedID.toString
	}
	
	override def completeRun( id : String, state : GoalState.State) : String = {
	  driverInfo.db withSession {
	   implicit session =>
	     val date : Date = new Date()
	     
	    val updateEndTime = for {e <- driverInfo.table if e.id === id.toInt} yield e.endTime //can't find a way to update multiple columns at once
	    updateEndTime.update(Some(new Timestamp (date.getTime())))
	    
	    val updateState = for {e <-driverInfo.table if e.id === id.toInt} yield e.state
	    updateState.update(state.toString())
	    
	  }
	  id // what should we return? Probably the RunID??
	}
	
	override def goalRunsForTrack(  trackDesc : TrackDescriptor , 
              startTime : Option[DateTime], endTime : Option[DateTime] ) : Seq[GoalRun] = {
	  var returnList : Seq[GoalRun] = null.asInstanceOf[Seq[GoalRun]]
	  driverInfo.db.withSession {
		   implicit session =>
		     
		   	 returnList=driverInfo.table.list.filter(g=>(g._2 == trackDesc.trackName &&
		         								g._3 == trackDesc.forUser &&
		         								g._4 == trackDesc.version &&
		         								(g._5 match { case v if !(v == "None") => v == trackDesc.variant
		         										 	  case v if (v == "None") => !trackDesc.variant.isDefined}) &&
		         								(startTime match { case Some(dateTime) =>
		         								  						new DateTime(g._8).compareTo(dateTime.asInstanceOf[DateTime]) >= 0
										    		 			   case None => true
					    		 							}) &&
					    		 				(endTime match {case Some(dateTime) if g._9.isDefined =>
					    		 				  					new DateTime(g._9.get).compareTo(dateTime.asInstanceOf[DateTime]) <= 0
									    		 				case Some(dateTime) if !g._9.isDefined => false
									    		 				case None => true
					    		 							})
		   			 							)).map(g => {
		   			 							  val gr = GoalRun(TrackDescriptor(g._2, g._3, g._4, Some(g._5)), 
															       	    g._6, dummyStringToWitness(g._7), new DateTime(g._8), 
															       	    g._9 match { case Some(timestamp) => Some(new DateTime(timestamp))
															       	    			 case None => null}, GoalState.withName(g._10))
													 gr.runId = g._1.toString
													 gr
		   			 							}).seq
			}
	  returnList
	}
	
	override  def goalRunsForGoal(  trackDesc : TrackDescriptor ,  
              goalName : String,
              startTime : Option[DateTime], endTime : Option[DateTime] ) : Seq[GoalRun] = {
	  
	  var returnList : Seq[GoalRun] = null.asInstanceOf[Seq[GoalRun]]
	  driverInfo.db.withSession {
		   implicit session =>
		   		   	 returnList=driverInfo.table.list.filter(g=>(g._2 == trackDesc.trackName &&
		         								g._3 == trackDesc.forUser &&
		         								g._4 == trackDesc.version &&
		         								(g._5 match {
		         										 	  case v if !(v == "None") => v == trackDesc.variant
		         										 	  case v if (v == "None") => !trackDesc.variant.isDefined}) &&
		         								g._6 == goalName &&
		         								(startTime match { case Some(dateTime) =>
		         								  						new DateTime(g._8).compareTo(dateTime.asInstanceOf[DateTime]) >= 0
										    		 			   case None => true
					    		 							}) &&
					    		 				(endTime match {case Some(dateTime) if g._9.isDefined =>
					    		 				  					new DateTime(g._9.get).compareTo(dateTime.asInstanceOf[DateTime]) <= 0
									    		 				case Some(dateTime) if !g._9.isDefined => false
									    		 				case None => true
					    		 							})
		   			 							)).map(g => {
		   			 							  val gr = GoalRun(TrackDescriptor(g._2, g._3, g._4, Some(g._5)), 
															       	    g._6, dummyStringToWitness(g._7), new DateTime(g._8), 
															       	    g._9 match { case Some(timestamp) => Some(new DateTime(timestamp))
															       	    			 case None => null}, GoalState.withName(g._10))
														gr.runId=g._1.toString
														gr
		   			 							}).seq
			}
	  returnList
	}	
	
	override def lookupGoalRun(  trackDesc : TrackDescriptor ,  
              goalName : String,
              witness : Witness ) : Seq[GoalRun] = {
		 var returnList : Seq[GoalRun] = null.asInstanceOf[Seq[GoalRun]]
		 driverInfo.db.withSession {
		   implicit session =>
		     
		     returnList = driverInfo.table.list.filter(g => (g._2 == trackDesc.trackName && // probably want: filter then list for efficiency. But it breaks comparison
		         										 	g._3 == trackDesc.forUser &&
		         										 	g._4 == trackDesc.version &&
		         										 	(g._5 match {
		         										 	  case v if !(v == "None") => v == trackDesc.variant
		         										 	  case v if (v == "None") => !trackDesc.variant.isDefined}) &&
		         										 	g._6 == goalName &&
		         										 	g._7 == dummyWitnessToString(witness)
		    		 									)).map(g => {
		    		 									  val gr = GoalRun(TrackDescriptor(g._2, g._3, g._4, Some(g._5)), 
															       	    g._6, dummyStringToWitness(g._7), new DateTime(g._8), 
															       	    g._9 match { case Some(timestamp) => Some(new DateTime(timestamp)) case None => null}, GoalState.withName(g._10)) 
															       	    gr.runId = g._1.toString
															       	    gr
		    		 											}).seq
		 }
		returnList
	}
	
	def lookupGoalRun( runID : String ) : Option[GoalRun] = { 
	  var returnGoal : GoalRun = null.asInstanceOf[GoalRun]
	  driverInfo.db withSession {
	   implicit session =>
	     val g = driverInfo.table.filter(_.id === runID.toInt).list
	   	
	     if (!g.isEmpty) {
	    	 val trackDesc :TrackDescriptor = TrackDescriptor(g(0)._2, g(0)._3, g(0)._4, Some(g(0)._5))
	     
		     val dtStart : DateTime = new DateTime(g(0)._8)
		     val dtEnd = g(0)._9 match { 
		       case Some(timestamp) => Some(new DateTime(timestamp))
		       case None => None
		     }
		     returnGoal = GoalRun(trackDesc, g(0)._6, dummyStringToWitness(g(0)._7), dtStart, dtEnd, GoalState.WaitingOnDependencies)
		     returnGoal.runId = g(0)._1.toString
		     Some(returnGoal)
	     } else {
	       None
	     }
		}
	}
	
	
	def getAllHistory() : Seq[GoalRun] = {
	  var returnList : Seq[GoalRun] = null.asInstanceOf[Seq[GoalRun]]
	  driverInfo.db.withSession {
		   implicit session =>
		   		   	 returnList=driverInfo.table.list.map(g => {
		   			 							  val gr = GoalRun(TrackDescriptor(g._2, g._3, g._4, Some(g._5)), 
															       	    g._6, dummyStringToWitness(g._7), new DateTime(g._8), 
															       	    g._9 match { case Some(timestamp) => Some(new DateTime(timestamp))
															       	    			 case None => null}, GoalState.withName(g._10))
														gr.runId=g._1.toString
														gr
		   			 							}).seq
			}
	  returnList
	}
	
	//dummy method - wait for Jerome
	def dummyWitnessToString ( witness : Witness) : String = {
	  "dummyWitness"
	}
	
	def dummyStringToWitness(string : String ) : Witness = {
	   null
	} 
  
}
object JDBCSlickTrackHistory extends JDBCSlickTrackHistory {
}
