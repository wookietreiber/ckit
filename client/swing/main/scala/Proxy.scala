/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *                                                                                               *
 *  Copyright  ©  2012  Christian Krause                                                         *
 *                                                                                               *
 *  Christian Krause  <christian.krause@ufz.de>                                                  *
 *                    <kizkizzbangbang@googlemail.com>                                           *
 *                                                                                               *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *                                                                                               *
 *  This file is part of 'ClusterKit'.                                                           *
 *                                                                                               *
 *  This project is free software: you can redistribute it and/or modify it under the terms      *
 *  of the GNU General Public License as published by the Free Software Foundation, either       *
 *  version 3 of the License, or any later version.                                              *
 *                                                                                               *
 *  This project is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;    *
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.    *
 *  See the GNU General Public License for more details.                                         *
 *                                                                                               *
 *  You should have received a copy of the GNU General Public License along with this project.   *
 *  If not, see <http://www.gnu.org/licenses/>.                                                  *
 *                                                                                               *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */


package ckit
package client
package swing

import akka.actor.Actor

import scala.swing._
import scala.swing.event._
import scala.util._

class Proxy extends Actor {
  def receive = sendToRemoteHandler orElse receiveFromRemoteHandler

  def sendToRemoteHandler: Receive = {
    case msg @ Protocol.JobDetail(_)  ⇒ SwingClient.remote ! msg
    case Protocol.JobList             ⇒ SwingClient.remote ! Protocol.JobList
    case msg @ Protocol.JobListFor(_) ⇒ SwingClient.remote ! msg
    case Protocol.QueueSummary        ⇒ SwingClient.remote ! Protocol.QueueSummary
    case Protocol.RuntimeSchedule     ⇒ SwingClient.remote ! Protocol.RuntimeSchedule
  }

  def receiveFromRemoteHandler: Receive = {
    case ckit.JobList(jobs: Seq[Job]) ⇒ Swing onEDT {
      val users = jobs.map(_.owner).distinct.mkString(", ")
      val panel = new JobListPane(Some(users), jobs)
      SwingClient.view.contents = panel
    }

    case data: ckit.JobDetail ⇒ Swing onEDT {
      val panel = new JobPane(data)
      SwingClient.view.contents = panel
    }

    case ckit.QueueSummaryList(qs) ⇒
      import scalax.chart.api._

      val data = for {
        q ← qs
        other = q.total - q.used - q.available
        pie = Seq(("unavailable",other),("used",q.used),("available",q.available)) filter { _._2 != 0 }
      } yield s"${q.name} with ${q.total} slots" → pie

      val dataset = data.toCategoryDataset
      val chart = MultiplePieChart(dataset, title = "Queue Status")
      chart.labelGenerator = None

      Swing onEDT {
        val panel = chart.toComponent
        panel.listenTo(panel.keys)
        panel.reactions += {
          case event @ KeyPressed(`panel`, Key.Left, Modifier.Alt, _) ⇒
            SwingClient.view.back()
        }
        SwingClient.view.contents = panel
      }

    case ckit.RuntimeSchedule(cluster, running, reserved) ⇒
      import java.util.Date
      import org.jfree.data.gantt._
      import org.jfree.data.time.SimpleTimePeriod
      import org.jfree.chart.{ ChartFactory, ChartPanel, JFreeChart }
      import org.jfree.chart.axis.{ CategoryAxis, DateAxis }
      import org.jfree.chart.plot.{ CategoryPlot, PlotOrientation, ValueMarker }
      import org.jfree.chart.renderer.category._

      val formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

      val dataset = new TaskSeriesCollection

      val yesterday = {
        import java.util.Calendar

        val date = Calendar.getInstance
        date.add(Calendar.DATE, -1)
        date.getTime
      }

      (running ++ reserved).groupBy(_.id).mapValues(_.sortBy(_.nodes.head)).toSeq.sortBy(_._2.head.nodes.head) foreach { case (id,jobs) ⇒
        val series = new TaskSeries(id.toString)

        for {
          job          ← jobs
          start        = {
            val start = formatter.parse(job.start)
            val date = if (start after yesterday) start else yesterday
            date.getTime
          }
          end          = start + job.runtime
          (node,slots) ← job.nodes
          _            ← 1 to slots
        } series add new Task(node, new SimpleTimePeriod(start, end))

        dataset add series
      }

      Swing onEDT {
        val dateAxis = new DateAxis()
        val categoryAxis = new CategoryAxis("Compute Nodes")
        categoryAxis.setCategoryMargin(0.0)
        categoryAxis.setLowerMargin(0.0)
        categoryAxis.setUpperMargin(0.0)

        val renderer = new ScheduleRenderer()
        renderer.setBarPainter(new StandardBarPainter())

        val plot = new CategoryPlot(dataset, categoryAxis, dateAxis, renderer)
        plot.setOrientation(PlotOrientation.HORIZONTAL)
        plot addRangeMarker new ValueMarker(System.currentTimeMillis)

        val chart = new JFreeChart("", JFreeChart.DEFAULT_TITLE_FONT, plot, true)
        ChartFactory.getChartTheme().apply(chart)

//        val range = renderer.findRangeBounds(dataset)
//        Console.println("upper: %s%nlower: %s%n".format(new Date(range.getUpperBound.toLong), new Date(range.getLowerBound.toLong)))

        val panel = new BorderPanel
        panel.peer.add(new ChartPanel(chart))

        SwingClient.view.contents = panel
        SwingClient.view.revalidate
      }

    case Failure(reason) ⇒ Swing onEDT {
      StatusBar.publish(reason)
    }
  }
}
