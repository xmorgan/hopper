package edu.colorado.hopper.client.android

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import edu.colorado.hopper.util._

import scala.collection.JavaConversions._
import scala.io.Source
import scala.sys.process._
import com.ibm.wala.analysis.pointers.HeapGraph
import com.ibm.wala.classLoader.IBytecodeMethod
import com.ibm.wala.classLoader.IClass
import com.ibm.wala.classLoader.IMethod
import com.ibm.wala.ipa.callgraph.AnalysisScope
import com.ibm.wala.ipa.callgraph.CGNode
import com.ibm.wala.ipa.callgraph.CallGraph
import com.ibm.wala.ipa.callgraph.propagation.HeapModel
import com.ibm.wala.ipa.cha.IClassHierarchy
import com.ibm.wala.ssa.IR
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction
import com.ibm.wala.ssa.SSAConditionalBranchInstruction
import com.ibm.wala.ssa.SSAInstruction
import com.ibm.wala.ssa.SSAInvokeInstruction
import com.ibm.wala.ssa.SSANewInstruction
import com.ibm.wala.ssa.SymbolTable
import com.ibm.wala.types.ClassLoaderReference
import com.ibm.wala.types.FieldReference
import com.ibm.wala.types.MethodReference
import com.ibm.wala.types.TypeName
import com.ibm.wala.types.TypeReference
import AndroidUIClient._
import edu.colorado.hopper.client.ClientTests
import edu.colorado.hopper.client.WalaAnalysisResults
import edu.colorado.hopper.piecewise.PiecewiseSymbolicExecutor
import edu.colorado.hopper.executor.TransferFunctions
import edu.colorado.hopper.piecewise.PiecewiseTransferFunctions
import edu.colorado.hopper.piecewise.RelevanceRelation
import edu.colorado.hopper.state.Qry
import edu.colorado.hopper.state.Var
import edu.colorado.hopper.state.Path
import WalaBlock.fromWalaBlock
import edu.colorado.hopper.util.CHAUtil
import edu.colorado.hopper.util.Types._
import edu.colorado.thresher.core.Options
import java.io.PrintWriter
import com.ibm.wala.ssa.SSAGetInstruction
import edu.colorado.droidel.driver.AndroidAppTransformer
import edu.colorado.droidel.driver.AndroidCGBuilder
import edu.colorado.droidel.constants.DroidelConstants
import edu.colorado.hopper.piecewise.DefaultPiecewiseSymbolicExecutor
import edu.colorado.droidel.driver.AbsurdityIdentifier


object AndroidUIClient {
  protected val DEBUG = false    
}

/** @param useJPhantom - should we preprocess app bytecodes with JPhantom? */
class AndroidUIClient(appPath : String, androidLib : File) {
  
  /**
   * perform DFS from each entrypoint, cutting off search when a library method is found
   * if library method is an Android library method, add an (entrypoint, library method) pair 
   */
  // TODO: this is inefficient. we could remember nodes from which Android methods were reachable in each iteration
  // and re-use this information for the next entrypoint
  def emitEntrypointLibrarySinkPairs(cg : CallGraph, entrypoints : Set[CGNode]) : Iterable[(CGNode,CGNode)] = {    
    def emitPairsInternal(entrypoint : CGNode, pairs : List[(CGNode,CGNode)]) = {      
      @annotation.tailrec
      def emitPairsRec(worklist : List[CGNode], pairs : List[(CGNode,CGNode)], seen : Set[CGNode]) : List[(CGNode,CGNode)] = worklist match {
        case cur :: worklist => 
          val (newWorklist, newPairs, newSeen) = cg.getSuccNodes(cur).foldLeft ((worklist, pairs, seen)) ((res, succ) => {
            val (worklist, pairs, seen) = res
            val newSeen = seen + succ
            if (newSeen.size > seen.size) { // have we already visited this node?
              if (ClassUtil.isLibrary(succ)) {
                if (isAndroidRelated(succ.getMethod().getDeclaringClass())) (worklist, (entrypoint, succ) :: pairs, newSeen) // found new pair
                else (worklist, pairs, newSeen) // library class, but not Android. cut off search along this branch
              } else (succ :: worklist, pairs, newSeen)
            } else (worklist, pairs, seen)
          })
          emitPairsRec(newWorklist, newPairs, newSeen)
        case Nil => pairs
      }      
      emitPairsRec(List(entrypoint), pairs, Set.empty[CGNode])
    }
    // iterate through entrypoints and collect pairs for each
    entrypoints.foldLeft (List.empty[(CGNode,CGNode)]) ((pairs, entrypoint) => emitPairsInternal(entrypoint, pairs))        
  }  
    
  def doUICheck() : Unit = {
    def isInterestingPair(pair : (CGNode, CGNode)) : Boolean = pair._2.getMethod().getName().toString() == "takePicture"
        
    // generate stubs and a specialized harness for the app
    val transformer = new AndroidAppTransformer(appPath, androidLib, useJPhantom = true, cleanupGeneratedFiles = true)
    // assume app has already been transformed by Droidel
    // TODO: validate by checking for bin/droidel_classes directory
    // transformer.transformApp
    val analysisScope = transformer.makeAnalysisScope(useHarness = true)
    val timer = new Timer
    timer.start
    println("Building call graph")
    val _walaRes = new AndroidCGBuilder(analysisScope, transformer.harnessClassName, transformer.harnessMethodName).makeAndroidCallGraph
    timer.printTimeTaken("Building call graph")
    new AbsurdityIdentifier(transformer.harnessClassName).getAbsurdities(_walaRes)
    val walaRes = new WalaAnalysisResults(_walaRes.cg, _walaRes.hg, _walaRes.hm, null) // hacky due to code re-use across Droidel and Scwala
      
    val cg = walaRes.cg
    
    val entrypoints = cg.getSuccNodes(cg.getFakeRootNode()).find(n => n.getMethod().getName().toString() == DroidelConstants.HARNESS_MAIN) match {
      case Some(androidMain) => 
        // TODO: problem: this will include not just the callback methods, but also methods called for the purposes of type inhabitation (e.g., constructors and static methods)
        // should we filter this list by known callbacks and lifecycle methods?
        cg.getSuccNodes(androidMain).toSet
      case None => sys.error(s"Couldn't find entrypoint node ${DroidelConstants.HARNESS_MAIN}; ${cg.getFakeRootNode().getIR()}")          
    }
          
    val pairs = emitEntrypointLibrarySinkPairs(cg, entrypoints)
    println("Got " + pairs.size + " (entrypoint, library call) pairs.")
    pairs.foreach(pair => println(ClassUtil.pretty(pair._1) + " -> " + ClassUtil.pretty(pair._2)))
    val interestingSinkMap = pairs.foldLeft (Map.empty[CGNode,Set[CGNode]]) ((map, pair) => 
      if (isInterestingPair(pair)) Util.updateSMap(map, pair._2, pair._1) else map
    )            
    
    interestingSinkMap.keys.foreach(snkNode => {
      println("Starting symbolic execution from interesting sink " + ClassUtil.pretty(snkNode))  
      val timer = new Timer
      timer.start
      val seenEntryNodes = Util.makeSet[CGNode]      
      val executor = new DefaultPiecewiseSymbolicExecutor(makeTransferFunctions(walaRes), getOrCreateRelevanceRelation(walaRes)) {                     
        // only go backward from entrypoints to methods called from the application scope
        // we don't want to follow library -> entrypoint edges backward because they usually correspond to imprecision in
        // the callgraph or to a callback we are already modeling manually in the harness
        override def getCallers(cg : CallGraph, callee : CGNode) : Iterable[CGNode] = { 
          val callers = cg.getPredNodes(callee).toIterable
          
          if (entrypoints.contains(callee)) {
            seenEntryNodes.add(callee)          
            callers.filter(caller => !ClassUtil.isLibrary(caller))
          } else callers
        }

        // disallow jumping for now
        override def piecewiseJumpRefuted(p : Path) : Boolean = false
        // disallow unproduceable constraint check (aka "eager relevance checking")
        // --leads to tons of false refutations if Android model is at all unsound 
        override def hasUnproduceableConstraint(p : Path) : Boolean = false
      }       
      
      val qry = Qry.make(Nil, snkNode, walaRes.hm)         
   
      val pathsAtEntry = executor.executeBackward(qry, None)
      println("Done with snk " + ClassUtil.pretty(snkNode))
      val ptEntrypoints = interestingSinkMap(snkNode)
      println("PT entrypoints (" + ptEntrypoints.size + ")"); ptEntrypoints.foreach(e => println(ClassUtil.pretty(e)))
      println("Thresher entrypoints (" + seenEntryNodes.size + ")"); seenEntryNodes.foreach(e => println(ClassUtil.pretty(e)))
      timer.stop
      println(s"Symbolic execution took ${timer.time} seconds")
    })
  }
  
  // hacky singleton to avoid creating more relevance relations than necessary. is careful to create a new relevance relation if we get a new program
  var rr : RelevanceRelation = null
  private def getOrCreateRelevanceRelation(walaRes : WalaAnalysisResults) : RelevanceRelation = 
    if (rr == null || (rr.cg != walaRes.cg || rr.hg != walaRes.hg || rr.hm != walaRes.hm || walaRes.cha != walaRes.cha)) 
      new RelevanceRelation(walaRes.cg, walaRes.hg, walaRes.hm, walaRes.cha)
    else rr
  
  // use piecewise transfer functions for better skipping of irrelevant callees
  def makeTransferFunctions(walaRes : WalaAnalysisResults) : TransferFunctions = {
    import walaRes._
    val rr = getOrCreateRelevanceRelation(walaRes)
    new PiecewiseTransferFunctions(cg, hg, hm, walaRes.cha, walaRes.modRef, rr) 
  }
      
  def isAndroidRelated(clazz : IClass) : Boolean = {
    val name = clazz.getName().toString()
    name.startsWith("Landroid") || name.startsWith("Ldalvik")|| name.startsWith("Lorg/apache/http")
  }    
           
  // only needed for debugging
  private def printCalledApplicationMethods(walaRes : WalaAnalysisResults) : Unit = {
    println("Called application methods:")
    walaRes.cg.foreach(n => if (!n.getMethod().isInit() && !n.getMethod().isClinit() && !ClassUtil.isLibrary(n))
      println(ClassUtil.pretty(n))
    )
  }  
}