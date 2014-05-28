package com.klout
package satisfaction.track

/*
Copyright 2012 Yann Ramin

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

/** 
 *  XXX FIXME
 *  Modified to handle Non-CronSchedules 
 *  
 */


// YY look up original source for reference
// purpose: schedule by frequency (ex// every 8 hours).

import akka.actor.{Cancellable, ActorRef, Actor}
import akka.event.Logging
import org.quartz.impl.StdSchedulerFactory
import java.util.Properties
import org.quartz._
import utils.Key
import org.joda.time.Period
import org.joda.time.DateTime
import org.joda.time.PeriodType


/**
 * Message to add a cron scheduler. Send this to the QuartzActor
 * @param to The ActorRef describing the desination actor
 * @param cron A string Cron expression
 * @param message Any message
 * @param reply Whether to give a reply to this message indicating success or failure (optional)
 */

case class AddCronSchedule(to: ActorRef, cron: String, message: Any, reply: Boolean = false, spigot: Spigot = OpenSpigot)

case class AddPeriodSchedule(to: ActorRef, period: Period, offsetTime : DateTime, message: Any, reply: Boolean = false, spigot: Spigot = OpenSpigot)

trait AddScheduleResult

/**
 * Indicates success for a scheduler add action.
 * @param cancel The cancellable allows the job to be removed later. Can be invoked directly -
 *               canceling will send an internal RemoveJob message
 */
case class AddScheduleSuccess(cancel: Cancellable) extends AddScheduleResult

/**
 * Indicates the job couldn't be added. Usually due to a bad cron expression.
 * @param reason The reason
 */
case class AddScheduleFailure(reason: Throwable) extends AddScheduleResult

/**
 * Remove a job based upon the Cancellable returned from a success call.
 * @param cancel
 */
case class RemoveJob(cancel: Cancellable)


/**
 * Internal class to make Quartz work.
 * This should be in QuartzActor, but for some reason Quartz
 * ends up with a construction error when it is.
 */
private class QuartzIsNotScalaExecutor() extends Job {
	def execute(ctx: JobExecutionContext) {
		val jdm = ctx.getJobDetail.getJobDataMap() // Really?
		val spigot = jdm.get("spigot").asInstanceOf[Spigot]
		if (spigot.open) {
			val msg = jdm.get("message")
			val actor = jdm.get("actor").asInstanceOf[ActorRef]
			actor ! msg
		}
	}
}

trait Spigot {
	def open: Boolean
}

object OpenSpigot extends Spigot {
  val open = true
}

/**
 * The base quartz scheduling actor. Handles a single quartz scheduler
 * and processes Add and Remove messages.
 */
class QuartzActor extends Actor { // receives msg from TrackScheduler
	val log = Logging(context.system, this)

	// Create a sane default quartz scheduler
	private[this] val props = new Properties()
	props.setProperty("org.quartz.scheduler.instanceName", context.self.path.name)
	props.setProperty("org.quartz.threadPool.threadCount", "1")
	props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
	props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true")	// Whoever thought this was smart shall be shot

	val scheduler = new StdSchedulerFactory(props).getScheduler


	/**
	 * Cancellable to later kill the job. Yes this is mutable, I'm sorry.
	 * @param job
	 */
	class CancelSchedule(val job: JobKey, val trig: TriggerKey) extends Cancellable {
		var cancelled = false

		def isCancelled: Boolean = cancelled

		def cancel() = {
			context.self ! RemoveJob(this)
			cancelled = true
			true
		}

	}

	override def preStart() {
		scheduler.start()
		log.info("Scheduler started")
	}

	override def postStop() {
		scheduler.shutdown()
	}
	
	def scheduleJob(to:ActorRef, schedBuilder:org.quartz.ScheduleBuilder[_ <: Trigger], message:Any,reply:Boolean,spigot:Spigot, offsetTime: Option[DateTime] = None) = {
			// Try to derive a unique name for this job
			// Using hashcode is odd, suggestions for something better?
			//val jobkey = new JobKey("%X".format((to.toString() + message.toString + cron + "job").hashCode))
			val jobkey = new JobKey("%X".format((to.toString() + message.toString + "job").hashCode))
			// Perhaps just a string is better :)
			///val trigkey = new TriggerKey(to.toString() + message.toString + cron + "trigger")
			val trigkey = new TriggerKey(to.toString() + message.toString +  "trigger")
			// We use JobDataMaps to pass data to the newly created job runner class
			val jd = org.quartz.JobBuilder.newJob(classOf[QuartzIsNotScalaExecutor])
			val jdm = new JobDataMap()
			jdm.put("spigot", spigot)
			jdm.put("message", message)
			jdm.put("actor", to)
			val job = jd.usingJobData(jdm).withIdentity(jobkey).build()

			try {
			    val triggerBuilder : TriggerBuilder[_ <:Trigger]  = org.quartz.TriggerBuilder.newTrigger().withIdentity(trigkey).forJob(job).withSchedule(schedBuilder)
			    offsetTime match {
			      case None => 
			       scheduler.scheduleJob( job, triggerBuilder.startNow.build)
			      case Some(offsetTime) =>
			       scheduler.scheduleJob( job, triggerBuilder.startAt(offsetTime.toDate).build)
			    }

				if (reply) // success case
					context.sender ! AddScheduleSuccess(new CancelSchedule(jobkey, trigkey))

			} catch { // Quartz will drop a throwable if you give it an invalid cron expression - pass that info on
				case e: Throwable =>
					log.error("Quartz failed to add a task: ", e)
					if (reply)
						context.sender ! AddScheduleFailure(e)

			}
		// I'm relatively unhappy with the two message replies, but it works
	}
	
	def builderForPeriod( period : Period ) : ScheduleBuilder[ _ <: Trigger] = {
	  period.getPeriodType match {
	    case pt: PeriodType if pt.getName.equals("Hours")=> 
	      SimpleScheduleBuilder.repeatHourlyForever( period.getHours() )
	    case pt: PeriodType if pt.toString.equals("Minutes") => 
	      SimpleScheduleBuilder.repeatMinutelyForever( period.getMinutes() )
	    case pt: PeriodType if pt.toString.equals("Seconds") => 
	      SimpleScheduleBuilder.repeatSecondlyForever( period.getSeconds() )
	    case pt: PeriodType if pt.toString.contains("Month") => 
	      CalendarIntervalScheduleBuilder.calendarIntervalSchedule
	      	.withIntervalInMonths( period.getMonths)
	      	.withIntervalInDays( period.getDays) /// 
	      	.withIntervalInHours( period.getHours)
	      	.withIntervalInMinutes( period.getMinutes)
	    case pt: PeriodType if pt.toString.contains("Week") => 
	      CalendarIntervalScheduleBuilder.calendarIntervalSchedule
	      	.withIntervalInWeeks( period.getWeeks)
	      	.withIntervalInDays( period.getDays) /// 
	      	.withIntervalInHours( period.getHours)
	      	.withIntervalInMinutes( period.getMinutes)
	  }
	}

	// Largely imperative glue code to make quartz work :)
	def receive = { // YY ? received here
		case RemoveJob(cancel) => cancel match {
			case cs: CancelSchedule => scheduler.deleteJob(cs.job); cs.cancelled = true
			case _ => log.error("Incorrect cancelable sent")
		}
		case AddCronSchedule(to, cron, message, reply, spigot) =>
		    val schedBuilder : ScheduleBuilder[_ <: Trigger] = org.quartz.CronScheduleBuilder.cronSchedule(cron)
		    scheduleJob(to,schedBuilder,message,reply,spigot)
			
		case AddPeriodSchedule(to, period, offsetTime, message, reply, spigot) =>
		    val schedBuilder : ScheduleBuilder[_ <: Trigger] = builderForPeriod( period)
		    scheduleJob(to,schedBuilder,message,reply,spigot) 
		case _ => //
		   /// XXX
	}


}