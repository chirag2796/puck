package puck.parser.gen

import puck.parser.{RuleSemiring, SymId, RuleStructure}
import epic.trees.{BinaryRule, UnaryRule}
import java.util.zip.{ZipOutputStream, ZipFile}
import com.nativelibs4java.opencl._
import puck.util.ZipUtil
import puck.linalg.CLMatrix
import org.bridj.Pointer
import epic.trees.BinaryRule
import epic.trees.UnaryRule
import puck.parser.SymId
import puck.parser.RuleStructure
import puck.PointerFreer
import scala.Array
import java.nio.{FloatBuffer, IntBuffer}

/**
 * Implement's Canny's viterbi algorithm
 *
 * @author dlwh
 */
case class CLViterbi(wgSize: Array[Int], kernel: CLKernel,
                     parentOffsets: Array[Int],
                     ruleScores: Array[Float],
                     ruleLefts: Array[Int],
                     ruleRights: Array[Int])(implicit context: CLContext) {
  private val parentOffsetsDev = context.createIntBuffer(CLMem.Usage.Input, IntBuffer.wrap(parentOffsets), true)
  private val ruleScoresDev = context.createFloatBuffer(CLMem.Usage.Input, FloatBuffer.wrap(ruleScores), true)
  private val ruleLeftsDev = context.createIntBuffer(CLMem.Usage.Input, IntBuffer.wrap(ruleLefts), true)
  private val ruleRightsDev = context.createIntBuffer(CLMem.Usage.Input, IntBuffer.wrap(ruleRights), true)

  def viterbi(masks: CLMatrix[Int],
              inside: CLMatrix[Float],
              chartIndices: Array[Int],
              lengths: Array[Int],
              root: Int,
              events: CLEvent*)(implicit queue: CLQueue):CLEvent = {
    require(masks.cols == inside.cols)
    queue.finish()

    val ptrCI = Pointer.pointerToArray[java.lang.Integer](chartIndices)
    val intBufferCI = queue.getContext.createIntBuffer(CLMem.Usage.InputOutput, chartIndices.length)
    val evCI = intBufferCI.write(queue, 0, chartIndices.length, ptrCI, false, events:_*)

    val ptrL = Pointer.pointerToArray[java.lang.Integer](lengths)
    val intBufferL = queue.getContext.createIntBuffer(CLMem.Usage.InputOutput, lengths.length)
    val evL = intBufferL.write(queue, 0, lengths.length, ptrL, false, events:_*)

    kernel.setArgs(masks.data.safeBuffer,
      inside.data.safeBuffer, intBufferCI, intBufferL,
      Integer.valueOf(lengths.length), Integer.valueOf(inside.rows),
      Integer.valueOf(root), parentOffsetsDev, ruleScoresDev, ruleLeftsDev, ruleRightsDev)

    val ev = kernel.enqueueNDRange(queue, Array(lengths.length* wgSize(0), wgSize(1)), wgSize, evCI, evL)
    PointerFreer.enqueue(ptrCI.release(), ev)
    PointerFreer.enqueue(intBufferCI.release(), ev)

    PointerFreer.enqueue(ptrL.release(), ev)
    PointerFreer.enqueue(intBufferL.release(), ev)
    ev
  }


  def write(out: ZipOutputStream) {
    ZipUtil.addKernel(out, "computeViterbiKernel", kernel)
    ZipUtil.serializedEntry(out, "ViterbiInts", wgSize)
    ZipUtil.serializedEntry(out, "ViterbiParents", parentOffsets)
    ZipUtil.serializedEntry(out, "ViterbiLeft", ruleLefts)
    ZipUtil.serializedEntry(out, "ViterbiRight", ruleRights)
    ZipUtil.serializedEntry(out, "ViterbiScores", ruleScores)
  }


}

object CLViterbi {
  def read(zf: ZipFile)(implicit ctxt: CLContext) = {
    val ints = ZipUtil.deserializeEntry[Array[Int]](zf.getInputStream(zf.getEntry("ViterbiInts")))
    val parentOffsets = ZipUtil.deserializeEntry[Array[Int]](zf.getInputStream(zf.getEntry("ViterbiParents")))
    val ruleLefts = ZipUtil.deserializeEntry[Array[Int]](zf.getInputStream(zf.getEntry("ViterbiLeft")))
    val ruleRights = ZipUtil.deserializeEntry[Array[Int]](zf.getInputStream(zf.getEntry("ViterbiRight")))
    val ruleScores = ZipUtil.deserializeEntry[Array[Float]](zf.getInputStream(zf.getEntry("ViterbiScores")))
    CLViterbi(ints, ZipUtil.readKernel(zf, "computeViterbiKernel"), parentOffsets, ruleScores, ruleLefts, ruleRights)
  }

  def make[C, L](structure: RuleStructure[C, L])(implicit context: CLContext, semiring: RuleSemiring) = {
    val blockSize = 32

    val wgSize = if (context.getDevices.head.toString.contains("Apple") && context.getDevices.head.toString.contains("Intel Core")) {
      Array(1, 1, 1)
    } else {
      val wgSizes = context.getDevices.head.getMaxWorkItemSizes
      val x = wgSizes(0) min blockSize
      Array(1, x toInt, 1)
    }

    val g = structure

    val (unaryOffsets, unaryScores, unaryChildren) = makeUnaryRuleArrays(g, g.unaryRules)
    val (unaryOffsetsT, unaryScoresT, unaryChildrenT) = makeUnaryRuleArrays(g, g.unaryTermRules)
    val (binaryOffsetsNN, binaryScoresNN, binaryLeftNN, binaryRightNN) = makeBinaryRuleArrays(g, g.nontermRules)
    val (binaryOffsetsNT, binaryScoresNT, binaryLeftNT, binaryRightNT) = makeBinaryRuleArrays(g, g.rightTermRules)
    val (binaryOffsetsTN, binaryScoresTN, binaryLeftTN, binaryRightTN) = makeBinaryRuleArrays(g, g.leftTermRules)
    val (binaryOffsetsTT, binaryScoresTT, binaryLeftTT, binaryRightTT) = makeBinaryRuleArrays(g, g.bothTermRules)

    val offsetsIntoMassiveDataArray = new Array[Int](binaryOffsetsNN.length * 4 + unaryOffsets.length * 2)
    assert(binaryOffsetsNN.length * 6 == offsetsIntoMassiveDataArray.length)
    val groupOffsets = copyScan(offsetsIntoMassiveDataArray)(binaryOffsetsNN, binaryOffsetsNT,
      binaryOffsetsTN, binaryOffsetsTT, unaryOffsets,
      unaryOffsetsT)

    val scores = epic.util.Arrays.concatenate(binaryScoresNN, binaryScoresNT, binaryScoresTN, binaryScoresTT, unaryScores, unaryScoresT)
    val left = epic.util.Arrays.concatenate(binaryLeftNN, binaryLeftNT, binaryLeftTN, binaryLeftTT, unaryChildren, unaryChildrenT)
    val right = epic.util.Arrays.concatenate(binaryRightNN, binaryRightNT, binaryRightTN, binaryRightTT)


    val offsetNames: Seq[String] = Seq(
      "BINARY_OFFSET_NN", "BINARY_OFFSET_NT", "BINARY_OFFSET_TN",
      "BINARY_OFFSET_TT",
      "UNARY_OFFSET", "UNARY_OFFSET_T")
    val defs = for((off, name) <- groupOffsets zip offsetNames) yield {
      s"#define $name $off"
    }


    val fullSource = (
//      offsetsIntoMassiveDataArray.take(1).mkString(s"__constant const int parentOffsets[] = {", ", ", "};\n\n")
        defs.mkString("\n")
        + source(structure, wgSize(1))
      )

    val prog = context.createProgram(fullSource)

    CLViterbi(wgSize, prog.createKernel("viterbi"), offsetsIntoMassiveDataArray, scores, left, right)
  }


  private def copyScan(dst: Array[Int])(arrays: Array[Int]*) = {
    var offset = 0
    var increment = 0
    for(arr <- arrays) {
      for(i <- 0 until arr.length) {
        dst(offset) = arr(i) + increment
        offset += 1
      }
      increment += arr.last
    }
    arrays.toArray.map(_.length).scan(0)(_ + _)
  }


  private def source[C, L](g: RuleStructure[C, L], wgSize: Int) = {

    """
      |
      |// TODO: silly global accesses to tree.
      |
      |
      |inline __global const float* CELL(__global const float* chart, int cellSize, int begin, int end, int length)  {
      |  int span = end-begin-1;
      |  return chart + cellSize * (begin + span * length - span * (span - 1) / 2);
      |}
      |
      |inline int CHART_SIZE(int dim) { return dim * (dim + 1) / 2; }
      |
      |typedef struct { int topSym, botSym, width, unused; } tree_t;
      |typedef struct { int left, right, split; float score; } splitInfo;
      |
      |#define WG_SIZE %d
      |
      |static int BestUnary(__global const float* insideBot, int parent,
      |    __constant const int* unaryOffsets,
      |    __global const float* unaryScores,
      |    __global const int* unaryChildren,
      |    __local float* bestScores,
      |    __local int* bestSyms) {
      |  int tid = get_local_id(1);
      |  int first_rule = unaryOffsets[parent];
      |  int last_rule = unaryOffsets[parent + 1];
      |
      |
      |  float bestScore = -300.0f;
      |  int bestSym = 0;
      |
      |  for(int r = first_rule + tid; r < last_rule; r += WG_SIZE) {
      |    int child = unaryChildren[r];
      |    float rScore = unaryScores[r];
      |    float cScore = insideBot[child];
      |    if (rScore + cScore >= bestScore) {
      |      bestScore = rScore + cScore;
      |      bestSym = child;
      |    }
      |  }
      |
      |  bestScores[tid] = bestScore;
      |  bestSyms[tid] = bestSym;
      |  barrier(CLK_LOCAL_MEM_FENCE);
      |
      |#pragma unroll
      |  for(int i = WG_SIZE >> 1; i > 0; i = i >> 1) {
      |    if (tid < i) {
      |      float score = bestScores[tid + i];
      |      if (score > bestScores[tid]) {
      |        bestScores[tid] = score;
      |        bestSyms[tid] = bestSyms[tid + i];
      |      }
      |    }
      |    barrier(CLK_LOCAL_MEM_FENCE);
      |  }
      |
      |  return bestSyms[0];
      |}
      |
      |static splitInfo BestSplit(splitInfo myInfo,
      |  __local float* bestScores,
      |  __local int* bestLefts,
      |  __local int* bestRights,
      |                           __local int* bestSplits) {
      |  int tid = get_local_id(1);
      |
      |  bestScores[tid] = myInfo.score;
      |  bestLefts[tid] = myInfo.left;
      |  bestRights[tid] = myInfo.right;
      |  bestSplits[tid] = myInfo.split;
      |  barrier(CLK_LOCAL_MEM_FENCE);
      |
      |#pragma unroll
      |  for(int i = WG_SIZE >> 1; i > 0; i = i >> 1) {
      |    if (tid < i) {
      |      float score = bestScores[tid + i];
      |      if (score > bestScores[tid]) {
      |        bestScores[tid] = score;
      |        bestLefts[tid] = bestLefts[tid + i];
      |        bestRights[tid] = bestRights[tid + i];
      |        bestSplits[tid] = bestSplits[tid + i];
      |      }
      |    }
      |    barrier(CLK_LOCAL_MEM_FENCE);
      |  }
      |
      |  return (splitInfo){bestLefts[0], bestRights[0], bestSplits[0], bestScores[0]};
      |}
      |
      |#define NN 0
      |#define NT -1
      |#define TN -2
      |#define TT -3
      |
      |static void BestBinary(
      |    splitInfo* info,
      |    __global const float* leftCell,
      |    __global const float* rightCell,
      |    int parent,
      |    __constant const int* binaryOffsets,
      |    __global const int* binaryLeft,
      |    __global const int* binaryRight,
      |    __global const float* binaryScores,
      |    int split) {
      |  int tid = get_local_id(1);
      |  int first_rule = binaryOffsets[parent];
      |  int last_rule = binaryOffsets[parent + 1];
      |
      |  float bestScore = info->score;
      |  for(int r = first_rule + tid; r < last_rule; r += WG_SIZE) {
      |    int lc = binaryLeft[r];
      |    int rc = binaryRight[r];
      |    float rScore = binaryScores[r];
      |    float lcScore = leftCell[lc];
      |    float rcScore = rightCell[rc];
      |    if (rScore + lcScore + rcScore >= bestScore) {
      |      bestScore = rScore + lcScore + rcScore;
      |      info->left = lc;
      |      info->right = rc;
      |      info->split = split;
      |    }
      |  }
      |
      |  info->score = bestScore;
      |}
      |
      |
      |__kernel void viterbi(__global tree_t* treeOut, __global float* insides,
      |    __global const int* cellOffsets, __global const int* lengths,
      |    int numSentences, int cellSize, int root,
      |    __constant const int* parentOffsets,
      |    __global const float* ruleScores,
      |    __global const int* ruleLefts,
      |    __global const int* ruleRights) {
      |  __local float bestScores[WG_SIZE];
      |  __local int bestLefts[WG_SIZE];
      |  __local int bestRights[WG_SIZE];
      |  __local int bestSplits[WG_SIZE];
      |  int tid = get_local_id(1);
      |  for(int sent = get_global_id(0); sent < numSentences; sent += get_global_size(0)) {
      |    __global tree_t* tree = treeOut + cellOffsets[sent];
      |    int length = lengths[sent];
      |    __global float* insideBot = insides + cellOffsets[sent] * cellSize;
      |    __global float* insideTop = insides + CHART_SIZE(length + 1) * cellSize;
      |
      |    if (tid == 0) {
      |      tree[0].topSym = root;
      |      tree[0].width = length;
      |    }
      |
      |    int begin = 0; // current leftmost position for span
      |
      |    for (int p = 0; p < 2 * length - 2; p++) {
      |      int width = tree[p].width;
      |      int end = begin + width; // rightmost.
      |      int botSym = tree[p].botSym;
      |
      |      // if -1, then we're skipping the unary layer and going straight to binary/terminal
      |      if (tree[p].topSym != -1) {
      |        int unaryOff = (width == 1) ? UNARY_OFFSET_T : UNARY_OFFSET;
      |        botSym = BestUnary(CELL(insideBot, cellSize, begin, end, length), tree[p].topSym,
      |            parentOffsets + unaryOff, ruleScores, ruleLefts, bestScores, bestLefts);
      |
      |        if (tid == 0)
      |          tree[p].botSym = botSym;
      |      }
      |
      |      if (width == 1) {
      |        // terminal, move on.
      |        begin += 1;
      |      } else {
      |        splitInfo info = (splitInfo){ 0, 0, begin + 1, -300.0f };
      |        BestBinary(&info,
      |            CELL(insideTop, cellSize, begin, end - 1, length),
      |            CELL(insideBot, cellSize, end - 1, end, length),
      |            botSym, parentOffsets + BINARY_OFFSET_NT, ruleLefts, ruleRights, ruleScores, NT);
      |
      |        BestBinary(&info,
      |            CELL(insideBot, cellSize, begin, begin + 1, length),
      |            CELL(insideTop, cellSize, begin + 1, end, length),
      |            botSym, parentOffsets + BINARY_OFFSET_TN, ruleLefts, ruleRights, ruleScores, TN);
      |
      |
      |        if (width == 2)
      |          BestBinary(&info,
      |              CELL(insideBot, cellSize, begin, begin + 1, length),
      |              CELL(insideBot, cellSize, begin + 1, end, length),
      |              botSym, parentOffsets + BINARY_OFFSET_TT, ruleLefts, ruleRights, ruleScores, TT);
      |
      |        for(int split = begin + 1; split < end; begin += 1) {
      |          __global const float* leftCell = CELL(insideTop, cellSize, begin, split, length);
      |          __global const float* rightCell = CELL(insideTop, cellSize, split, end, length);
      |          BestBinary(&info,
      |              leftCell,
      |              rightCell,
      |              botSym, parentOffsets + BINARY_OFFSET_NN, ruleLefts, ruleRights, ruleScores, split);
      |        }
      |
      |        splitInfo best = BestSplit(info, bestScores, bestLefts, bestRights, bestSplits);
      |
      |
      |        if (tid == 0) {
      |          int bestSplit = best.split;
      |          int bestConfig = bestSplit < 0 ? -bestSplit : NN;
      |
      |          bestSplit = (bestConfig == NN) ? bestSplit
      |            : bestConfig == TN ? begin + 1
      |            : end - 1; // TT or NT
      |
      |          if (bestConfig >> 1 == 0) {// Left N
      |            tree[p + 1].topSym = info.left;
      |          } else { // Left T
      |            tree[p + 1].topSym = -1;
      |            tree[p + 1].botSym = info.left;
      |          }
      |
      |          if ( (bestConfig & 1) == 0) { // Right N
      |            tree[p + 2 * (bestSplit - begin)].topSym = info.right;
      |          } else { // Right T
      |            tree[p + 2 * (bestSplit - begin)].topSym = -1;
      |            tree[p + 2 * (bestSplit - begin)].botSym = info.right;
      |          }
      |
      |          tree[p + 1].width = bestSplit - begin;
      |          tree[p + 2 * (bestSplit-begin)].width = end - bestSplit;
      |        }
      |
      |      }
      |
      |    }
      |
      |  }
      |}
      |
    """.stripMargin.format(wgSize)
  }

  private def makeUnaryRuleArrays[L, C](g: RuleStructure[C, L], rules: IndexedSeq[(UnaryRule[SymId[C, L]], Int)]): (Array[Int], Array[Float], Array[Int]) = {
    val triples = for ((rule, id) <- rules) yield {
      (rule.parent.gpu, rule.child.gpu, g.scores(id))
    }

    val (unaryParents, unaryChildren, unaryScores) = triples.sortBy(_._1).unzip3

    // scan of first/last
    val offsets = 0 +: Array.tabulate(g.nontermIndex.size)(unaryParents.lastIndexOf(_) + 1)
    assert(offsets.last == unaryChildren.length)
    (offsets, unaryScores.toArray, unaryChildren.toArray)
  }

  private def makeBinaryRuleArrays[L, C](g: RuleStructure[C, L], rules: IndexedSeq[(BinaryRule[SymId[C, L]], Int)]): (Array[Int], Array[Float], Array[Int], Array[Int]) = {
    val triples = for ((rule, id) <- rules) yield {
      (rule.parent.gpu, (rule.left.gpu, rule.right.gpu), g.scores(id))
    }

    val (binaryParents, binaryChildren, binaryScores) = triples.sortBy(_._1).unzip3

    val (left, right) = binaryChildren.unzip

    // scan of first/last
    val offsets = 0 +: Array.tabulate(g.nontermIndex.size)(binaryParents.lastIndexOf(_) + 1)
    for(o <- (offsets.length-1) to 1 by -1) {
      if(o == offsets.length - 1  && offsets(o) == 0) offsets(o) = binaryChildren.length
      else if(offsets(o) == 0) offsets(o) = offsets(o + 1)
    }
    assert(offsets.last == binaryChildren.length, offsets.last + " " + binaryChildren.length + " " + offsets.toIndexedSeq)
    (offsets, binaryScores.toArray, left.toArray, right.toArray)
  }
}
