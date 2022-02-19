import java.lang.Thread
import java.nio.file.{Path, Paths}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, FlowShape, Graph, IOResult, OverflowStrategy}
import akka.stream.scaladsl.{Balance, Broadcast, Compression, FileIO, Flow, GraphDSL, Keep, Merge, RunnableGraph, Sink, Source}
import akka.util.ByteString

import ujson.Value.Value

import scala.concurrent.{ExecutionContextExecutor, Future}


case class counter(name: String,
                   version: String){
  var NumOfDependencies: Int = 0
  var NumOfDevDependencies: Int = 0
  def IncreaseNumOfDep(num: Int):Int ={
    NumOfDependencies += num
    NumOfDependencies
  }
  def IncreaseNumOfDevDep (num: Int):Int={
    NumOfDevDependencies += num
    NumOfDevDependencies
  }
}

case class package2(name: Value,
                   version: Value,
                    Dependencies: Value = "default",
                    devDependencies: Value = "default"
                   )

object Assignment1 extends App {
  implicit val actorSystem: ActorSystem = ActorSystem("Assignment1")
  implicit val dispatcher: ExecutionContextExecutor = actorSystem.dispatcher
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()

  val resourcesFolder: String = "src/main/resources"
  val pathGZFile: Path = Paths.get(s"$resourcesFolder/packages.txt.gz")
  val source: Source[ByteString, Future[IOResult]] = FileIO.fromPath(pathGZFile)
  source.buffer(10, OverflowStrategy.backpressure)
  val link: String = "https://registry.npmjs.org/"

  val flowUnzip: Flow[ByteString, ByteString, NotUsed] = Compression.gunzip()
  val flowString: Flow[ByteString, String, NotUsed] = Flow[ByteString].map(_.utf8String)
  val flowSplitWords: Flow[String, String, NotUsed] = Flow[String].mapConcat(_.split("\n").toList)
  val flowLink: Flow[String,String,NotUsed] = Flow[String].map(w => toLink(w))
  val flowToJson: Flow[String, Value, NotUsed] = Flow[String].map{w =>
    Thread.sleep(3000)
    getText(w)}
  //  temp: whole json files
  //  output: {versions1:{version1:{dependencies:{}},version2:{dependencies:{}}...},
  //            versions2:{version1:{dependencies:{}},version2:{dependencies:{}}...}}
  // output2:  version1:{dependencies:{}},version2:{dependencies:{}...}
  // output class2: ujson.Arr
  val flowVersions: Flow[Value, Value, NotUsed] = Flow[Value].map(_.obj("versions")).map(version => {
    version.obj.map(element => {
      element._2
    })
  })
//.flatMapConcat
  val flowSiplify: Flow[Value,Value,NotUsed] = Flow[Value].map(m => m(0))

//  Flow[Batch].flatMapConcat

  val flowCreate: Flow[Value,(package2,counter),NotUsed] = Flow[Value].map(m => {createPackageAndCounter(m)})
//
//  val composedFlow = flowUnzip.via(flowString).via(flowSplitWords).via(flowLink).via(flowToJson).via(flowVersions)
//    .via(flowSiplify).via(flowCreate)

  val composedFlow = flowUnzip.via(flowString).via(flowSplitWords).via(flowLink).via(flowToJson).via(flowVersions)
    .via(flowSiplify).via(flowCreate)

  def depFiltr = Flow[(package2,counter)].map(m =>{
   val tmpPackage = m._1
    val tmpCounter = m._2
    tmpCounter
  })

  def devDepFilter = Flow[(package2,counter)].map(m =>{
    val tmpPackage = m._1
    val tmpCounter = m._2
    tmpCounter
  })

  val parallelStage = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val dispatchVer = builder.add(Balance[(package2,counter)](2))
    val dispatchDep = builder.add(Broadcast[(package2,counter)](2))
    val dispatchDep2 = builder.add(Broadcast[(package2,counter)](2))
    val counterObj = builder.add(Merge[counter](2))
    val counterObj2 = builder.add(Merge[counter](2))
    val mergeCollection = builder.add(Merge[counter](2))

    dispatchVer.out(0) ~> dispatchDep ~> depFiltr ~>  counterObj.in(0)
    dispatchDep ~> devDepFilter ~> counterObj.in(1)
    dispatchVer.out(1) ~> dispatchDep2 ~> depFiltr.async ~>  counterObj2.in(0)
    dispatchDep2 ~> devDepFilter.async ~>  counterObj2.in(1)

    counterObj ~> mergeCollection.in(0)
    counterObj2 ~> mergeCollection.in(1)

    FlowShape(dispatchVer.in, mergeCollection.out)

  })

  val sink: Sink[counter, Future[Done]] = Sink.foreach(printResult)
  val runnableGraph: RunnableGraph[Future[Done]] = source
    .via(composedFlow)
    .via(parallelStage)
    .toMat(sink)(Keep.right)

  runnableGraph.run().foreach(_ => actorSystem.terminate())

  def toLink(str: String): String ={
    val str1 = link + str
    str1
  }

  def getText(str:String):Value ={
    val response = requests.get(str)
    ujson.read(response.text)
  }

  def createPackageAndCounter(m: Value):(package2,counter) = {
    val tuple = (m.obj("name"),m.obj("version"))
    var pack = package2(tuple._1, tuple._2)
    val count = counter(tuple._1.toString(), tuple._2.toString())
    if(m.obj.contains("dependencies") & m.obj.contains("devDependencies")){
      pack = package2(tuple._1, tuple._2, m.obj("dependencies"), m.obj("devDependencies"))
      count.IncreaseNumOfDep(pack.Dependencies.obj.size)
      count.IncreaseNumOfDevDep(pack.devDependencies.obj.size)
    }
    else if(m.obj.contains("dependencies") & !m.obj.contains("devDependencies")){
      pack = package2(tuple._1, tuple._2, m.obj("dependencies"), "default")
      counter(m.obj("name").toString(),m.obj("version").toString())
      count.IncreaseNumOfDep(pack.Dependencies.obj.size)
    }
    else if (!m.obj.contains("dependencies") & m.obj.contains("devDependencies")){
      pack = package2(tuple._1, tuple._2, "default" , m.obj("devDependencies"))
      counter(m.obj("name").toString(),m.obj("version").toString())
      count.IncreaseNumOfDevDep(pack.devDependencies.obj.size)
    }
    else {pack
      count}
    (pack,count)
  }

  def printResult(counter:counter) = {
    println(s"Analysing ${counter.name}...")
    println(s"Version: ${counter.version}, Dependencies: ${counter.NumOfDependencies}, DevDependencies: ${counter.NumOfDevDependencies}")
  }

}
