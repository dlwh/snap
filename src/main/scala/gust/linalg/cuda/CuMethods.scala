package gust.linalg.cuda

import breeze.linalg.support.{CanCollapseAxis, CanTranspose, CanSlice2}


import jcuda.jcublas.{cublasOperation, cublasHandle, JCublas2, cublasDiagType, cublasFillMode, cublasSideMode}
import gust.util.cuda
import jcuda.runtime.{cudaError, cudaMemcpyKind, cudaStream_t, JCuda}
import cuda._
import breeze.linalg.operators._
import breeze.linalg._
import breeze.linalg.support.{CanCollapseAxis, CanTranspose, CanSlice2}
import org.bridj.Pointer

import jcuda.driver._
import jcuda.jcurand.{curandRngType, curandGenerator}
import breeze.math.{Semiring, Ring}
import breeze.numerics._
import breeze.generic.UFunc
import scala.reflect._

import com.github.fommil.netlib.LAPACK.{getInstance=>lapack}
import com.github.fommil.netlib.ARPACK
import org.netlib.util.intW
import spire.syntax.cfor._
import CuWrapperMethods._

/**
 * Created by piotrek on 21.05.2014.
 */
class CuMethods {

}

object CuMethods {

//  val hostOne = Pointer.pointerToFloat(1.0f).toCuPointer
//  val hostMinusOne = Pointer.pointerToFloat(-1.0f).toCuPointer
//  val hostZero = Pointer.pointerToFloat(0.0f).toCuPointer


  def lapackTest {

    val A = DenseMatrix((12.0f, -51.0f, 4.0f), (6.0f, 167.0f, -68.0f), (-4.0f, 24.0f, -41.0f))
    val T = DenseMatrix.zeros[Float](3, 3)
    val work = Array.ofDim[Float](18)
    val tau = Array.ofDim[Float](3)
    val info = new intW(0)
    lapack.sgeqrf(3, 3, A.data, 0, A.majorStride, tau, 0, work, 0, 18, info)

    lapack.slarft("F", "C", 3, 3, A.data, 0, A.majorStride, tau, 0, T.data, 0, T.majorStride)

    def printTau {
      println("tau")
      for (i <- 0 until tau.length) { print(tau(i) + "  ") }
      println
    }

    println("A\n" + A)
    println("T\n" + T)
    printTau
  }

  def QR(A: CuMatrix[Float])(implicit handle: cublasHandle): (CuMatrix[Float], DenseVector[Float]) = {
    // pointers to scalars (for sgemm):
    val oneArr = Array(1.0f)
    val hostOne = jcuda.Pointer.to(oneArr)
    val zeroArr = Array(0.0f)
    val hostZero = jcuda.Pointer.to(zeroArr)
    val minusOneArr = Array(-1.0f)
    val hostMinusOne = jcuda.Pointer.to(minusOneArr)


    val nb = 64     // block size
    val ldaArr = Array(A.majorStride)
    val lda = jcuda.Pointer.to(ldaArr)
    val n = A.cols  // size of matrix

    // gpu matrices:
    val gpu_matrix = CuMatrix.create[Float](n, n); gpu_matrix := A
    val gpu_TV = CuMatrix.create[Float](nb, n)
    val gpu_TVA = CuMatrix.create[Float](nb, n)
    //val gpu_T = gpu_TVA
    // might as well not use gpu_T at all

    // cpu matrices:
    val cpu_matrix = A.toDense
    val cpu_tau = DenseVector.zeros[Float](n)
    val cpu_work = Array.ofDim[Float](n * n * nb)
    val cpu_T = DenseMatrix.zeros[Float](nb, nb)

    var h, w = 0
    val es = gpu_matrix.elemSize
    val info = new intW(0)
    val lwork = cpu_work.length

    // prep for launching the kernel:
    JCudaDriver.setExceptionsEnabled(true)
    JCudaDriver.cuInit(0)
    val device = new CUdevice()
    JCudaDriver.cuDeviceGet(device, 0)
    val context = new CUcontext()
    //JCudaDriver.cuCtxGetCurrent(context)
    JCudaDriver.cuCtxCreate(context, 0, device)

    val module = new CUmodule()
    JCudaDriver.cuModuleLoad(module, "src/main/resources/gust/linalg/cuda/enforceLU.ptx")

    val enfLU = new CUfunction()
    // I have to use the mangled name here but there has to be something wrong,
    // there's "extern C" in the kernel code so it shouldn't mangle the name (TODO fix)
    JCudaDriver.cuModuleGetFunction(enfLU, module, "_Z9enforceLUPfi")

    // we don't need to upload anything onto the GPU -- it's already there
    cfor(0)(_ < n, _ + nb) { i => {
      h = n - i
      w = if (h < nb) h else nb

      if (i > 0) {

        SgemmNN(nb, w, h+nb, hostOne, gpu_TV, 0, 0, gpu_matrix, i-nb, i, hostZero,
                gpu_TVA, 0, 0)

        SgemmNN(h+nb, w, nb, hostMinusOne, gpu_matrix, i-nb, i-nb, gpu_TVA, 0, 0, hostOne,
                gpu_matrix, i-nb, i)

        downloadFloat(n, w, cpu_matrix, 0, i, gpu_matrix, 0, i)

        SgemmNN(nb, h-nb, h+nb, hostOne, gpu_TV, 0, 0, gpu_matrix, i-nb, i+nb, hostZero,
                gpu_TVA, 0, 0)

        SgemmNN(h+nb, h-nb, nb, hostMinusOne, gpu_matrix, i-nb, i-nb, gpu_TVA, 0, 0, hostOne,
                gpu_matrix, i-nb, i+nb)

      }

      // factorization on CPU
      // additional params after matrices are offsets (like i in float *A;  A+i)
      lapack.sgeqrf(h, w, cpu_matrix.data, cpu_matrix.linearIndex(i, i), cpu_matrix.majorStride,
                    cpu_tau.data, i, cpu_work, 0, lwork, info)


      if (h > nb) {
        lapack.slarft("F", "C", h, w, cpu_matrix.data, cpu_matrix.linearIndex(i, i), cpu_matrix.majorStride,
                      cpu_tau.data, i, cpu_T.data, 0, cpu_T.majorStride)

        // transpose:
        cfor(0)(_ < nb, _ + 1) { j => {
          cfor(0)(_ < j, _ + 1) { k => {
            cpu_T(j, k) = cpu_T(k, j)
            cpu_T(k, j) = 0.0f
          }}
        }}


        // upload to GPU:
        // gpu_T --> gpu_TVA
        uploadFloat(nb, nb, gpu_TVA, 0, 0, cpu_T, 0, 0)
        uploadFloat(h, nb, gpu_matrix, i, i, cpu_matrix, i, i)

        // enforceLU
        // basically --> zero out everything above the main diagonal and put 1's on the diagonal
        // kernel launch:
        val params = jcuda.Pointer.to(
          jcuda.Pointer.to(gpu_matrix.offsetPointer.withByteOffset(gpu_matrix.linearIndex(i, i) * es)),
          lda
        )

        JCudaDriver.cuLaunchKernel(enfLU, nb, 1, 1, nb, 1, 1, 0, null, params, null)
        JCudaDriver.cuCtxSynchronize()

        SgemmNT(nb, h, nb, hostOne, gpu_TVA, 0, 0, gpu_matrix, i, i, hostZero, gpu_TV, 0, 0)
      }
    }}

//    println("Results: ")
//    //println("GPU T: \n" + gpu_T)
//    println("GPU TV: \n" + gpu_TV)
//    println("GPU TVA: \n" + gpu_TVA)
//    println("CPU MATRIX: \n" + cpu_matrix)
//    println("CPU T: \n" + cpu_T)
//    println("CPU TAU: \n" + cpu_tau)

    (CuMatrix.fromDense(cpu_matrix), cpu_tau)
  }

  /**
   * Performs a householder trasnsform (in-place)
   * @param A
   * @param t if true, the vector v will be extracted from a row of A, instead of a column (t == false)
   * @param v
   * @param beta
   * @param C matrix for the intermediate result of v*v' --> passed as a parameter to avoid unnecessary allocations
   */
  def householderDouble(A: CuMatrix[Double], t: Boolean, v: CuMatrix[Double], beta: Double, C: CuMatrix[Double])(implicit handle: cublasHandle) {
    if (v.cols != 1) {
      println("v not a vector")
      return
    }

    if (C.rows != C.cols) {
      println("C not a square matrix")
      return
    }

    if (C.rows != v.rows) {
      println("C has to be of dim. v.rows x v.rows")
      return
    }

    val b = Array(beta)
    val one = Array(1.0)
    val zero = Array(0.0)
    val minusOne = Array(-1.0)
    var curesult = 0

    // C = beta * v * v'
    curesult = JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_T,
      C.rows, C.cols, v.cols,
      jcuda.Pointer.to(b), v.data.toCuPointer.withByteOffset(v.offset * v.elemSize), v.majorStride,
      v.data.toCuPointer.withByteOffset(v.offset * v.elemSize), v.majorStride,
      jcuda.Pointer.to(zero), C.data.toCuPointer, C.rows)

    println("[DEBUG] First dgemm: " + curesult)

    // A = A + (-1.0) * C * A
    curesult = JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
                         C.rows, A.cols, A.rows, jcuda.Pointer.to(minusOne),
                         C.offsetPointer, C.majorStride,
                         A.offsetPointer, A.majorStride,
                         jcuda.Pointer.to(one),
                         A.offsetPointer, A.majorStride)

    println("[DEBUG] Second dgemm: " + curesult)
  }


    /**
     * Generates parameters for the householder transform based on the vector x
     * i.e. H = (I - beta*v*v') --> it computes beta and v
     *
     * I'm not sure whether to do it on the GPU or on CPU
     */
  def householderGenerateDouble(x: CuMatrix[Double])(implicit handle: cublasHandle): (Double, Double, CuMatrix[Double]) = {
      val alpha = Array(0.0)

      val v: CuMatrix[Double] = CuMatrix.create[Double](x.rows, x.cols)
      v := x

      val xnorm = Array(0.0)		// norm of x
      JCublas2.cublasDnrm2(handle, x.rows, x.offsetPointer, 1, jcuda.Pointer.to(xnorm))

      // get x(0)
      val x0 = Array(0.0)
      JCuda.cudaMemcpy(jcuda.Pointer.to(x0), x.offsetPointer, x.elemSize,
                       cudaMemcpyKind.cudaMemcpyDeviceToHost)

      alpha(0) = xnorm(0) * (if (x0(0) >= 0.0) 1.0 else -1.0)

      // get v(0):
      val v0 = Array(x0(0))
      val beta = if (Math.abs(alpha(0)) < 1e-20) 0.0
                 else 1.0 / (Math.abs(alpha(0)) * (Math.abs(alpha(0)) + Math.abs(v0(0))))

      v0(0) += alpha(0)

      // now copy back the v0:
      JCuda.cudaMemcpy(v.offsetPointer, jcuda.Pointer.to(v0), v.elemSize,
                       cudaMemcpyKind.cudaMemcpyHostToDevice)

      (-alpha(0), beta, v)
    }



  /**
   * LU factorization, based on algorithm from noctua-blog.com
   * One important thing is that the matrix doesn't have to be square.
   * This is algorithm uses pivoting and block-approach.
   *
   * This is actually almost the same as Volkov's LU with the difference of the
   * decomposition of the block on the diagonal being performed GPU-side (which is fine, since we
   * don't use the super-fast mkl that he uses).
   *
   * The returned matrix contains both L and U -- diagonal of L is implicit, as in this
   * variant it's all ones.
   */
  def LUFloat(A: CuMatrix[Float])(implicit handle: cublasHandle): (CuMatrix[Float], CuMatrix[Float]) = {
    val P: Array[Int] = (0 until A.rows).toArray
    val d_A = CuMatrix.create[Float](A.rows, A.cols)
    d_A := A
    val blockSize = 64
    val es = d_A.elemSize
    val lda = A.rows

    val one = Array(1.0f)
    val onePtr = jcuda.Pointer.to(one)
    val minusOne = Array(-1.0f)
    val minusOnePtr = jcuda.Pointer.to(minusOne)

    // this one should be in place to inject the results into the big matrix
    // passing the pointer instead of the whole matrix allows us to
    // do C-style pointer manipulation
    def LUSingleBlockFloat(M: Int, N:Int, ADataPointer: CuPointer, pOffset: Int): Unit = {
      //      val d_A = CuMatrix.create[Float](A.rows, A.cols)
      //      // creating a copy of the matrix
      //      //    JCuda.cudaMemcpy2D(d_A.data.toCuPointer, A.majorStride * A.elemSize,
      //      //      A.data.toCuPointer,
      //      //      A.majorStride * A.elemSize, A.cols * A.elemSize, A.rows, cudaMemcpyKind.cudaMemcpyDeviceToDevice)
      //      d_A := A

      //      val M = A.rows
      //      val N = A.cols
      val minDim = if (M < N) M else N
      //val ADataPointer = d_A.offsetPointer
      val intRes = Array(0)
      val intResPtr = jcuda.Pointer.to(intRes)
      val A_ii = Array(0.0f)
      val AiiPtr = jcuda.Pointer.to(A_ii)
      val alpha = Array(0.0f)

      cfor(0)(_ < minDim, _ + 1) { i => {
        JCublas2.cublasIsamax(handle, M - i, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          1, intResPtr)

        val pivotRow = i + intRes(0) - 1  // -1 because of cublas' 1-based indexing
        val ip1 = i + 1
        P(i + pOffset) = pivotRow + pOffset

        if (pivotRow != i) {
          // If you put N instead of A.cols, you have to uncomment the lines in LUBlocked
          JCublas2.cublasSswap(handle, N, ADataPointer.withByteOffset(d_A.linearIndex(pivotRow, 0) * es),
            lda, ADataPointer.withByteOffset(d_A.linearIndex(i, 0) * es), lda)
        }

        JCuda.cudaMemcpy2D(AiiPtr, d_A.majorStride * d_A.elemSize,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          d_A.majorStride * es, d_A.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)
        // this one works as well:
        //      curesult =  JCublas2.cublasGetVector(1, es.toInt, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
        //                               1, AiiPtr, 1)

        if (Math.abs(A_ii(0)) < 1e-20) {
          println("Matrix is singular")
          return
        }

        if (ip1 < M) {
          alpha(0) = 1.0f / A_ii(0)
          JCublas2.cublasSscal(handle, M - ip1, jcuda.Pointer.to(alpha),
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, i) * es), 1)
        }

        if (ip1 < minDim) {
          JCublas2.cublasSger(handle, M - ip1, N - ip1, minusOnePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, i) * es), 1,
            ADataPointer.withByteOffset(d_A.linearIndex(i, ip1) * es), lda,
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, ip1) * es), lda)

        }
      }
      }

      //d_A
    }

    def LUBlockedFloat(d_A: CuMatrix[Float]): Unit = {
      val M = d_A.rows
      val N = d_A.cols
      val minSize = if (M < N) M else N
      val ADataPointer = d_A.offsetPointer

      if (blockSize >= minSize) {
        LUSingleBlockFloat(M, N, ADataPointer, 0)
        return
      }

      cfor(0)(_ < minSize, _ + blockSize) { i => {
        val realBlockSize = if (minSize - i < blockSize) minSize - i else blockSize
        LUSingleBlockFloat(M - i, realBlockSize, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es), i)

        // uncomment these lines if necessary
        val mm = if (M < i+realBlockSize) M else i + realBlockSize

        cfor(i)(_ < mm - 1, _ + 1) { p => {
          // P(p) = P(p) + i // ???
          if (P(p) != p) {
            JCublas2.cublasSswap(handle, i, ADataPointer.withByteOffset(d_A.linearIndex(p, 0) * es),
                                 lda, ADataPointer.withByteOffset(d_A.linearIndex(P(p), 0) * es), lda)

            JCublas2.cublasSswap(handle, N-i-realBlockSize, ADataPointer.withByteOffset(d_A.linearIndex(p, i+realBlockSize) * es), lda,
                                 ADataPointer.withByteOffset(d_A.linearIndex(P(p), i + realBlockSize) * es), lda)
          }

        }}

        JCublas2.cublasStrsm(handle, cublasSideMode.CUBLAS_SIDE_LEFT, cublasFillMode.CUBLAS_FILL_MODE_LOWER,
          cublasOperation.CUBLAS_OP_N, cublasDiagType.CUBLAS_DIAG_UNIT,
          realBlockSize, N - i - realBlockSize, onePtr,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es), lda,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i+realBlockSize) * es), lda)


        if(i + realBlockSize < M) {
          JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
            M - i - realBlockSize, N - i - realBlockSize, realBlockSize, minusOnePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(i+realBlockSize, i) * es), lda,
            ADataPointer.withByteOffset(d_A.linearIndex(i, i+realBlockSize) * es), lda,
            onePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(i+realBlockSize, i+realBlockSize) * es), lda)
        }
      }
      }
    }

    LUBlockedFloat(d_A)
    // the pivoting matrix is Float (and not Int), because right now gust doesn't support
    // matrix multiplication other than Float*Float or Double*Double.
    // Of course, the swapping of rows is quite bad performance-wise but returning the
    // matrix and not a vector is a good thing, I guess

    // transforming permutation vector into permutation matrix
    // this could be done with a trick: cublasSetVector with incy = PM.majorStride + 1,
    // but I can't get CuMatrix.ones to work right now
    val PM = CuMatrix.fromDense(DenseMatrix.eye[Float](A.rows))

    cfor(0)(_ < PM.rows - 1, _ + 1) { i => {
      if (P(i) != i) {
        JCublas2.cublasSswap(handle, PM.cols,
          PM.offsetPointer.withByteOffset(PM.linearIndex(i, 0) * PM.elemSize), PM.majorSize,
          PM.offsetPointer.withByteOffset(PM.linearIndex(P(i), 0) * PM.elemSize), PM.majorSize)
      }
    }}

    (d_A, PM)
  }


  def LUDouble(A: CuMatrix[Double])(implicit handle: cublasHandle): (CuMatrix[Double], CuMatrix[Double]) = {
    val P: Array[Int] = (0 until A.rows).toArray
    val d_A = CuMatrix.create[Double](A.rows, A.cols)
    d_A := A
    val blockSize = 64
    val es = d_A.elemSize
    val lda = A.rows

    val one = Array(1.0)
    val onePtr = jcuda.Pointer.to(one)
    val minusOne = Array(-1.0)
    val minusOnePtr = jcuda.Pointer.to(minusOne)


    def LUSingleBlockDouble(M: Int, N:Int, ADataPointer: CuPointer, pOffset: Int): Unit = {

      val minDim = if (M < N) M else N
      val intRes = Array(0)
      val intResPtr = jcuda.Pointer.to(intRes)
      val A_ii = Array(0.0)
      val AiiPtr = jcuda.Pointer.to(A_ii)
      val alpha = Array(0.0)

      cfor(0)(_ < minDim, _ + 1) { i => {
        JCublas2.cublasIdamax(handle, M - i, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          1, intResPtr)

        val pivotRow = i + intRes(0) - 1  // -1 because of cublas' 1-based indexing
        val ip1 = i + 1
        P(i + pOffset) = pivotRow + pOffset

        if (pivotRow != i) {
          // A.cols <-> N + uncomment in LUBlocked
          JCublas2.cublasDswap(handle, N, ADataPointer.withByteOffset(d_A.linearIndex(pivotRow, 0) * es),
            lda, ADataPointer.withByteOffset(d_A.linearIndex(i, 0) * es), lda)
        }

        JCuda.cudaMemcpy2D(AiiPtr, d_A.majorStride * d_A.elemSize,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          d_A.majorStride * es, d_A.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)
        // this one works as well:
        //      curesult =  JCublas2.cublasGetVector(1, es.toInt, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es),
        //                               1, AiiPtr, 1)

        if (Math.abs(A_ii(0)) < 1e-20) {
          println("Matrix is singular")
          return
        }

        if (ip1 < M) {
          alpha(0) = 1.0f / A_ii(0)
          JCublas2.cublasDscal(handle, M - ip1, jcuda.Pointer.to(alpha),
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, i) * es), 1)
        }

        if (ip1 < minDim) {
          JCublas2.cublasDger(handle, M - ip1, N - ip1, minusOnePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, i) * es), 1,
            ADataPointer.withByteOffset(d_A.linearIndex(i, ip1) * es), lda,
            ADataPointer.withByteOffset(d_A.linearIndex(ip1, ip1) * es), lda)

        }
      }
      }

      //d_A
    }

    def LUBlockedDouble(d_A: CuMatrix[Double]): Unit = {
      val M = d_A.rows
      val N = d_A.cols
      val minSize = if (M < N) M else N
      val ADataPointer = d_A.offsetPointer

      if (blockSize >= minSize) {
        LUSingleBlockDouble(M, N, ADataPointer, 0)
        return
      }

      cfor(0)(_ < minSize, _ + blockSize) { i => {
        val realBlockSize = if (minSize - i < blockSize) minSize - i else blockSize
        LUSingleBlockDouble(M - i, realBlockSize, ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es), i)

        val mm = if (M < i+realBlockSize) M else i + realBlockSize

        cfor(i)(_ < mm - 1, _ + 1) { p => {
          // P(p) = P(p) + i // ???
          if (P(p) != p) {
            JCublas2.cublasDswap(handle, i, ADataPointer.withByteOffset(d_A.linearIndex(p, 0) * es),
              lda, ADataPointer.withByteOffset(d_A.linearIndex(P(p), 0) * es), lda)

            JCublas2.cublasDswap(handle, N-i-realBlockSize, ADataPointer.withByteOffset(d_A.linearIndex(p, i+realBlockSize) * es), lda,
              ADataPointer.withByteOffset(d_A.linearIndex(P(p), i + realBlockSize) * es), lda)
          }

        }}

        JCublas2.cublasDtrsm(handle, cublasSideMode.CUBLAS_SIDE_LEFT, cublasFillMode.CUBLAS_FILL_MODE_LOWER,
          cublasOperation.CUBLAS_OP_N, cublasDiagType.CUBLAS_DIAG_UNIT,
          realBlockSize, N - i - realBlockSize, onePtr,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i) * es), lda,
          ADataPointer.withByteOffset(d_A.linearIndex(i, i+realBlockSize) * es), lda)


        if(i + realBlockSize < M) {
          JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
            M - i - realBlockSize, N - i - realBlockSize, realBlockSize, minusOnePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(i+realBlockSize, i) * es), lda,
            ADataPointer.withByteOffset(d_A.linearIndex(i, i+realBlockSize) * es), lda,
            onePtr,
            ADataPointer.withByteOffset(d_A.linearIndex(i+realBlockSize, i+realBlockSize) * es), lda)
        }
      }
      }
    }

    LUBlockedDouble(d_A)

    val PM = CuMatrix.fromDense(DenseMatrix.eye[Double](A.rows))

    cfor(0)(_ < PM.rows - 1, _ + 1) { i => {
      if (P(i) != i) {
        JCublas2.cublasSswap(handle, PM.cols,
          PM.offsetPointer.withByteOffset(PM.linearIndex(i, 0) * PM.elemSize), PM.majorSize,
          PM.offsetPointer.withByteOffset(PM.linearIndex(P(i), 0) * PM.elemSize), PM.majorSize)
      }
    }}

    (d_A, PM)
  }


  /**
   * LU decomposition using the cublas(S|D)getrfBatched -- not very fast
   * and works only for square matrices
   * @param A
   * @param handle
   * @return
   */
  def LUFloatCublas(A: CuMatrix[Float])(implicit handle: cublasHandle): (CuMatrix[Float], CuMatrix[Float]) = {

    if (A.rows != A.cols) {
      println("A has to be a square matrix.")
      return (A, CuMatrix.fromDense(DenseMatrix.eye[Float](A.rows))) // got to return something, perhaps
                                                                     // an eye is better than null
    }

    val d_A = CuMatrix.create[Float](A.rows, A.cols)
    d_A := A

    val P = CuMatrix.create[Int](d_A.rows, 1)
    val info = CuMatrix.create[Int](1, 1)

    val A_ptr = jcuda.Pointer.to(d_A.offsetPointer)
    val d_Aptr = new jcuda.Pointer()

    JCuda.cudaMalloc(d_Aptr, jcuda.Sizeof.POINTER)
    JCuda.cudaMemcpy(d_Aptr, A_ptr, 1 * jcuda.Sizeof.POINTER, cudaMemcpyKind.cudaMemcpyHostToDevice)

    JCublas2.cublasSgetrfBatched(handle, d_A.rows, d_Aptr, d_A.majorSize,
                                 P.offsetPointer, info.offsetPointer, 1)

    // transforming permutation vector into permutation matrix
    val h_P = P.toDense
    val PM = CuMatrix.fromDense(DenseMatrix.eye[Float](A.rows))

    cfor(0)(_ < PM.rows - 1, _ + 1) { i => {
      if (h_P(i, 0) != i + 1) {   // the returned vector uses 1-based indexing
        JCublas2.cublasSswap(handle, PM.cols,
          PM.offsetPointer.withByteOffset(PM.linearIndex(i, 0) * PM.elemSize), PM.majorSize,
          PM.offsetPointer.withByteOffset(PM.linearIndex(h_P(i, 0) - 1, 0) * PM.elemSize), PM.majorSize)
      }
    }}

    (d_A, PM)
  }

  def LUDoubleCublas(A: CuMatrix[Double])(implicit handle: cublasHandle): (CuMatrix[Double], CuMatrix[Double]) = {

    if (A.rows != A.cols) {
      println("A has to be a square matrix.")
      return (A, CuMatrix.fromDense(DenseMatrix.eye[Double](A.rows)))
    }

    val d_A = CuMatrix.create[Double](A.rows, A.cols)
    d_A := A

    val P = CuMatrix.create[Int](d_A.rows, 1)
    val info = CuMatrix.create[Int](1, 1)

    val A_ptr = jcuda.Pointer.to(d_A.offsetPointer)
    val d_Aptr = new jcuda.Pointer()

    JCuda.cudaMalloc(d_Aptr, jcuda.Sizeof.POINTER)
    JCuda.cudaMemcpy(d_Aptr, A_ptr, 1 * jcuda.Sizeof.POINTER, cudaMemcpyKind.cudaMemcpyHostToDevice)

    JCublas2.cublasDgetrfBatched(handle, d_A.rows, d_Aptr, d_A.majorSize,
      P.offsetPointer, info.offsetPointer, 1)

    val h_P = P.toDense
    val PM = CuMatrix.fromDense(DenseMatrix.eye[Double](A.rows))

    cfor(0)(_ < PM.rows - 1, _ + 1) { i => {
      if (h_P(i, 0) != i + 1) {   // the returned vector uses 1-based indexing
        JCublas2.cublasDswap(handle, PM.cols,
                             PM.offsetPointer.withByteOffset(PM.linearIndex(i, 0) * PM.elemSize), PM.majorSize,
                             PM.offsetPointer.withByteOffset(PM.linearIndex(h_P(i, 0) - 1, 0) * PM.elemSize), PM.majorSize)
      }
    }}

    (d_A, PM)
  }


  /** The algorithms below are a Scala-port of the CUDA C version from OrangeOwlSolutions
    * Gauss-Jordan solve of a linear system
    * It is actually quite good for small systems (<= 256**2)
    *
    * The result ends up in the d_B vector */
  def solveSlow(A: CuMatrix[Double], B: CuMatrix[Double])(implicit handle: cublasHandle): CuMatrix[Double] = {
    if (A.rows != B.rows) {
      println("Number of rows in A must be the same as number of rows in B")
      return B
    }

    if (B.cols != 1) {
      println("B has to be a column vector")
      return B
    }

    if (A.rows != A.cols) {
      println("A has to be a square matrix")
      return B
    }

    val d_A = CuMatrix.create[Double](A.rows, A.cols)
    val d_B = CuMatrix.create[Double](B.rows, B.cols)
    d_A := A
    d_B := B
    val N = d_A.rows
    // val e_s = A.elemSize   // elems in A and B are the same size

    // this is a workaround for writing:   double alpha;   ..., &alpha   as in C
    val alpha = Array(0.0)
    val beta = Array(0.0)
    val A_ii = Array(0.0)
    val B_i = Array(0.0)
    val a_iiPtr = jcuda.Pointer.to(A_ii)
    val b_iPtr = jcuda.Pointer.to(B_i)
    var cuResult = 0 // just temporary, to check for errors

    val d_R = CuMatrix.zeros[Double](N, 1) // a vector with ratios for every iteration

    for (i <- 0 until (N - 1)) {

      // moving the current elem. on the diagonal to a variable, to use it later
      cuResult = JCuda.cudaMemcpy2D(a_iiPtr, d_A.majorStride * d_A.elemSize,
        d_A.data.toCuPointer.withByteOffset(d_A.linearIndex(i, i) * d_A.elemSize),
        d_A.majorStride * d_A.elemSize, d_A.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)


      if (Math.abs(A_ii(0)) < 1e-20) {
        println("A_ii: " + A_ii(0) + ", i: " + i)
        // this could be solved by pivoting...
        println("Division by (nearly) zero.")
        return B
      }

      // copies the whole row "under" the element d_A(i, i) to d_R
      JCublas2.cublasDcopy(handle, N - (i + 1), d_A.data.toCuPointer.withByteOffset(d_A.linearIndex(i + 1, i) * d_A.elemSize),
        1, d_R.data.toCuPointer.withByteOffset(d_R.linearIndex(i + 1, 0) * d_R.elemSize), 1)


      alpha(0) = 1.0 / A_ii(0)
      // d_R *= alpha
      JCublas2.cublasDscal(handle, N - (i + 1), jcuda.Pointer.to(alpha),
        d_R.data.toCuPointer.withByteOffset(d_R.linearIndex(i + 1, 0) * d_R.elemSize), 1)

      JCuda.cudaMemcpy2D(b_iPtr, d_B.majorStride * d_B.elemSize,
        d_B.data.toCuPointer.withByteOffset(d_B.linearIndex(i, 0) * d_B.elemSize),
        d_B.majorStride * d_B.elemSize, d_B.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)

      alpha(0) = -B_i(0)
      // d_B += d_R * alpha
      JCublas2.cublasDaxpy(handle, N - (i + 1), jcuda.Pointer.to(alpha),
        d_R.data.toCuPointer.withByteOffset(d_R.linearIndex(i + 1, 0) * d_R.elemSize), 1,
        d_B.data.toCuPointer.withByteOffset(d_B.linearIndex(i + 1, 0) * d_B.elemSize), 1)

      alpha(0) = -1.0
      beta(0) = 1.0

      // update of the whole submatrix (col_vector * row_vector == matrix)
      JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
        N - (i + 1), N - i, 1, jcuda.Pointer.to(alpha),
        d_R.data.toCuPointer.withByteOffset(d_R.linearIndex(i + 1, 0) * d_R.elemSize), N,
        d_A.data.toCuPointer.withByteOffset(d_A.linearIndex(i, i) * d_A.elemSize), N, jcuda.Pointer.to(beta),
        d_A.data.toCuPointer.withByteOffset(d_A.linearIndex(i + 1, i) * d_A.elemSize), N)

    }

    // solve the triangular system:
    JCublas2.cublasDtrsv(handle, cublasFillMode.CUBLAS_FILL_MODE_UPPER, cublasOperation.CUBLAS_OP_N,
      cublasDiagType.CUBLAS_DIAG_NON_UNIT, N,
      d_A.data.toCuPointer, d_A.majorSize,
      d_B.data.toCuPointer, 1)

    d_B
  }


  /**
   * Stripe version using cfor for Double values
   * Performance as with whiles, but more readable
   *
   * @param A
   * @param B
   * @param handle
   * @return d_B
   */
  def solveDouble(A: CuMatrix[Double], B: CuMatrix[Double])(implicit handle: cublasHandle): CuMatrix[Double] = {
    if (A.rows != B.rows) {
      println("Number of rows in A must be the same as number of rows in B")
      return B
    }

    if (B.cols != 1) {
      println("B has to be a column vector")
      return B
    }

    if (A.rows != A.cols) {
      println("A has to be a square matrix")
      return B
    }

    // copy the matrices:
    val d_A = CuMatrix.create[Double](A.rows, A.cols)
    d_A := A
//    JCuda.cudaMemcpy2D(d_A.data.toCuPointer, A.majorStride * A.elemSize,
//      A.data.toCuPointer,
//      A.majorStride * A.elemSize, A.cols * A.elemSize, A.rows, cudaMemcpyKind.cudaMemcpyDeviceToDevice)

    val d_B = CuMatrix.create[Double](B.rows, B.cols)
    d_B := B
//    JCuda.cudaMemcpy2D(d_B.data.toCuPointer, B.elemSize,
//      B.data.toCuPointer,
//      B.elemSize, B.elemSize, B.rows, cudaMemcpyKind.cudaMemcpyDeviceToDevice)

    val N = d_A.rows
    val matrixBlockSize = 256
    val matrixStripeSize = 128
    val numBlocks = N / matrixBlockSize + (if (N % matrixBlockSize == 0) 0 else 1)

    //val alpha = Array(0.0)
    val beta = Array(0.0)
    val A_ii = Array(0.0)
    val B_i = Array(0.0)
    val a_iiPtr = jcuda.Pointer.to(A_ii)
    val b_iPtr = jcuda.Pointer.to(B_i)
    val one = Array(1.0)
    val onePtr = jcuda.Pointer.to(one)
    val minusOne = Array(-1.0)
    val minusOnePtr = jcuda.Pointer.to(minusOne)

    val d_R = CuMatrix.zeros[Double](N, matrixBlockSize)
    val AMemPointer = d_A.offsetPointer
    val BMemPointer = d_B.offsetPointer
    val RMemPointer = d_R.offsetPointer
    val es = d_A.elemSize

    val vectAii = Array.ofDim[Double](N)
    val vectBi = Array.ofDim[Double](N)

    cfor(0)(_ < numBlocks, _ + 1) { iB => {

      val i0 = iB * N / numBlocks
      val i1 = (iB + 1) * N / numBlocks


      // iB.iB
      cfor(i0)(_ < i1, _ + 1) { i => {
        JCuda.cudaMemcpy2D(a_iiPtr, d_A.majorStride * d_A.elemSize,
          AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          d_A.majorStride * es, d_A.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)

        if (Math.abs(A_ii(0)) < 1e-20) {
          println("A_ii: " + A_ii(0) + ", i: " + i)
          println("Division by (nearly) zero.")
          return B
        }

        // cublasDcopy(handle, i1-(i+1), d_A+(i+1)+i*N, 1, d_R+(i+1)+(i-i0)*N, 1)
        JCublas2.cublasDcopy(handle, i1 - (i + 1),
          AMemPointer.withByteOffset(d_A.linearIndex(i + 1, i) * es), 1,
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1)

        beta(0) = 1.0 / A_ii(0)
        // cublasDscal(handle, i1-(i+1), &beta, d_R+(i+1)+(i-i0)*N, 1)
        JCublas2.cublasDscal(handle, i1 - (i + 1), jcuda.Pointer.to(beta),
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1)

        JCuda.cudaMemcpy2D(b_iPtr, d_B.majorStride * d_B.elemSize,
          BMemPointer.withByteOffset(d_B.linearIndex(i, 0) * es),
          d_B.majorStride * es, es, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)

        beta(0) = -B_i(0)
        //cublasDaxpy(handle, i1-(i+1), &beta, d_R+(i+1)+(i-i0)*N, 1, d_B+(i+1), 1)
        JCublas2.cublasDaxpy(handle, i1 - (i + 1), jcuda.Pointer.to(beta),
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1,
          BMemPointer.withByteOffset(d_B.linearIndex(i + 1, 0) * es), 1)

        //alpha(0) = -1.0
        //beta(0) = 1.0
        // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i1-(i+1), i1-i, 1, &alpha,
        // d_R+(i+1)+(i-i0)*N, N, d_A+i+i*N, N, &beta, d_A+(i+1)+i*N, N)
        JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N, i1 - (i + 1), i1 - i, 1,
          minusOnePtr,
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), N,
          AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es), N,
          onePtr,
          AMemPointer.withByteOffset(d_A.linearIndex(i + 1, i) * es), N)

      }
      }

      cfor(iB + 1)(_ < numBlocks, _ + 1) { kB => {
        val k0 = kB * N / numBlocks
        val k1 = (kB + 1) * N / numBlocks

        val numStripes = (i1 - i0) / matrixStripeSize + (if ((i1 - i0) % matrixStripeSize == 0) 0 else 1)

        // for (int iQ=0; iQ<Num_Stripes; iQ++) {
        cfor(0)(_ < numStripes, _ + 1) { iQ => {

          val i_0 = i0 + iQ * (i1 - i0) / numStripes
          val i_1 = i0 + (iQ + 1) * (i1 - i0) / numStripes

          // for (int i=i_0; i<i_1; i++)
          cfor(i_0)(_ < i_1, _ + 1) { i => {
            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i_1-(i+1), k1-k0, 1, &alpha,
            // d_R+(i+1)+(i-i0)*N, N, d_A+i+k0*N, N, &beta, d_A+(i+1)+k0*N, N)
            JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              i_1 - (i + 1), k1 - k0, 1, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i, k0) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(i + 1, k0) * es), N)

          }
          }

          // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i1-i_1, k1-k0, i_1-i_0, &alpha,
          // d_R+i_1+(i_0-i0)*N, N, d_A+i_0+k0*N, N, &beta, d_A+i_1+k0*N, N))
          JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
            i1 - i_1, k1 - k0, i_1 - i_0, minusOnePtr,
            RMemPointer.withByteOffset(d_R.linearIndex(i_1, i_0 - i0) * es), N,
            AMemPointer.withByteOffset(d_A.linearIndex(i_0, k0) * es), N,
            onePtr,
            AMemPointer.withByteOffset(d_A.linearIndex(i_1, k0) * es), N)

        }
        }

      }
      }

      if (iB < numBlocks - 1) {
        // double* vectAii = (double*)malloc((i1-i0)*sizeof(double));
        // double* vectBi	= (double*)malloc((i1-i0)*sizeof(double));
        // cublasGetVector(i1-i0, sizeof(double), d_A+i0+i0*N, N+1, vectAii, 1)
        // puts the i1-i0 elems from diagonal in vectAii:
        JCublas2.cublasGetVector(i1 - i0, es.toInt,
          AMemPointer.withByteOffset(d_A.linearIndex(i0, i0) * es), N + 1,
          jcuda.Pointer.to(vectAii), 1)
        // cublasGetVector(i1-i0, sizeof(double), d_B+i0, 1, vectBi, 1)
        JCublas2.cublasGetVector(i1 - i0, es.toInt,
          BMemPointer.withByteOffset(d_B.linearIndex(i0, 0) * es), 1,
          jcuda.Pointer.to(vectBi), 1)


        // jB.iB
        cfor(iB + 1)(_ < numBlocks, _ + 1) { jB => {
          val j0 = jB * N / numBlocks
          val j1 = (jB + 1) * N / numBlocks

          val numStripes = (i1 - i0) / matrixStripeSize + (if ((i1 - i0) % matrixStripeSize == 0) 0 else 1)

          var iQ = 0
          while (iQ < numStripes) {
            val i_0 = i0 + iQ * (i1 - i0) / numStripes
            val i_1 = i0 + (iQ + 1) * (i1 - i0) / numStripes

            var i = i_0
            while (i < i_1) {

              // cublasDcopy(handle, j1-j0, d_A+j0+i*N, 1, d_R+j0+(i-i0)*N, 1)
              JCublas2.cublasDcopy(handle, j1 - j0,
                AMemPointer.withByteOffset(d_A.linearIndex(j0, i) * es), 1,
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1)

              beta(0) = 1.0 / vectAii(i - i0)
              // cublasDscal(handle, j1-j0, &beta, d_R+j0+(i-i0)*N , 1)
              JCublas2.cublasDscal(handle, j1 - j0, jcuda.Pointer.to(beta),
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1)

              beta(0) = -vectBi(i - i0)
              // cublasDaxpy(handle, j1-j0, &beta, d_R+j0+(i-i0)*N, 1, d_B+j0, 1)
              JCublas2.cublasDaxpy(handle, j1 - j0, jcuda.Pointer.to(beta),
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1,
                BMemPointer.withByteOffset(d_B.linearIndex(j0, 0) * es), 1)

              // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, i_1-i, 1, &alpha,
              // d_R+j0+(i-i0)*N, N, d_A+i+i*N, N, &beta, d_A+j0+i*N, N)
              JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
                j1 - j0, i_1 - i, 1, minusOnePtr,
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), N,
                AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es), N,
                onePtr,
                AMemPointer.withByteOffset(d_A.linearIndex(j0, i) * es), N)

              i += 1
            }

            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, i1-i_1, i_1-i_0, &alpha,
            // d_R+j0+(i_0-i0)*N, N, d_A+i_0+i_1*N, N, &beta, d_A+j0+i_1*N, N)
            JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              j1 - j0, i1 - i_1, i_1 - i_0, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(j0, i_0 - i0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i_0, i_1) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(j0, i_1) * es), N)


            iQ += 1
          }

        }
        }

        // jB.kB
        // for (int jB = iB + 1; jB < Num_Blocks; jB++) {
        cfor(iB + 1)(_ < numBlocks, _ + 1) { jB => {

          val j0 = jB * N / numBlocks
          val j1 = (jB + 1) * N / numBlocks

          cfor(iB + 1)(_ < numBlocks, _ + 1) { kB => {
            val k0 = kB * N / numBlocks
            val k1 = (kB + 1) * N / numBlocks

            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, k1-k0, i1-i0, &alpha,
            // d_R+j0, N, d_A+i0+k0*N, N, &beta, d_A+j0+k0*N, N)
            JCublas2.cublasDgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              j1 - j0, k1 - k0, i1 - i0, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(j0, 0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i0, k0) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(j0, k0) * es), N)

          }
          }

        }
        }

      }

    }
    }

    // solve the triangular system:
    JCublas2.cublasDtrsv(handle, cublasFillMode.CUBLAS_FILL_MODE_UPPER, cublasOperation.CUBLAS_OP_N,
      cublasDiagType.CUBLAS_DIAG_NON_UNIT, N,
      AMemPointer, d_A.majorSize,
      BMemPointer, 1)

    // the solution vector is in d_B
    d_B
  }


  /**
   * Stripe version using cfor for Float values
   *
   * @param A
   * @param B
   * @param handle
   * @return d_B
   */
  def solveFloat(A: CuMatrix[Float], B: CuMatrix[Float])(implicit handle: cublasHandle): CuMatrix[Float] = {
    if (A.rows != B.rows) {
      println("Number of rows in A must be the same as number of rows in B")
      return B
    }

    if (B.cols != 1) {
      println("B has to be a column vector")
      return B
    }

    if (A.rows != A.cols) {
      println("A has to be a square matrix")
      return B
    }

    // copy the matrices:
    val d_A = CuMatrix.create[Float](A.rows, A.cols)
    d_A := A
//    JCuda.cudaMemcpy2D(d_A.data.toCuPointer, A.majorStride * A.elemSize,
//      A.data.toCuPointer,
//      A.majorStride * A.elemSize, A.cols * A.elemSize, A.rows, cudaMemcpyKind.cudaMemcpyDeviceToDevice)

    val d_B = CuMatrix.create[Float](B.rows, B.cols)
    d_B := B
//    JCuda.cudaMemcpy2D(d_B.data.toCuPointer, B.elemSize,
//      B.data.toCuPointer,
//      B.elemSize, B.elemSize, B.rows, cudaMemcpyKind.cudaMemcpyDeviceToDevice)

    val N = d_A.rows
    val matrixBlockSize = 256
    val matrixStripeSize = 128
    val numBlocks = N / matrixBlockSize + (if (N % matrixBlockSize == 0) 0 else 1)

    //val alpha = Array(0.0)
    val beta = Array(0.0f)
    val A_ii = Array(0.0f)
    val B_i = Array(0.0f)
    val a_iiPtr = jcuda.Pointer.to(A_ii)
    val b_iPtr = jcuda.Pointer.to(B_i)
    val one = Array(1.0f)
    val onePtr = jcuda.Pointer.to(one)
    val minusOne = Array(-1.0f)
    val minusOnePtr = jcuda.Pointer.to(minusOne)

    val d_R = CuMatrix.zeros[Float](N, matrixBlockSize)
    val AMemPointer = d_A.offsetPointer
    val BMemPointer = d_B.offsetPointer
    val RMemPointer = d_R.offsetPointer
    val es = d_A.elemSize

    val vectAii = Array.ofDim[Float](N)
    val vectBi = Array.ofDim[Float](N)

    cfor(0)(_ < numBlocks, _ + 1) { iB => {

      val i0 = iB * N / numBlocks
      val i1 = (iB + 1) * N / numBlocks


      // iB.iB
      cfor(i0)(_ < i1, _ + 1) { i => {
        JCuda.cudaMemcpy2D(a_iiPtr, d_A.majorStride * d_A.elemSize,
          AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es),
          d_A.majorStride * es, d_A.elemSize, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)

        if (Math.abs(A_ii(0)) < 1e-20) {
          println("A_ii: " + A_ii(0) + ", i: " + i)
          println("Division by (nearly) zero.")
          return B
        }

        // cublasDcopy(handle, i1-(i+1), d_A+(i+1)+i*N, 1, d_R+(i+1)+(i-i0)*N, 1)
        JCublas2.cublasScopy(handle, i1 - (i + 1),
          AMemPointer.withByteOffset(d_A.linearIndex(i + 1, i) * es), 1,
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1)

        beta(0) = 1.0f / A_ii(0)
        // cublasDscal(handle, i1-(i+1), &beta, d_R+(i+1)+(i-i0)*N, 1)
        JCublas2.cublasSscal(handle, i1 - (i + 1), jcuda.Pointer.to(beta),
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1)

        JCuda.cudaMemcpy2D(b_iPtr, d_B.majorStride * d_B.elemSize,
          BMemPointer.withByteOffset(d_B.linearIndex(i, 0) * es),
          d_B.majorStride * es, es, 1, cudaMemcpyKind.cudaMemcpyDeviceToHost)

        beta(0) = -B_i(0)
        //cublasDaxpy(handle, i1-(i+1), &beta, d_R+(i+1)+(i-i0)*N, 1, d_B+(i+1), 1)
        JCublas2.cublasSaxpy(handle, i1 - (i + 1), jcuda.Pointer.to(beta),
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), 1,
          BMemPointer.withByteOffset(d_B.linearIndex(i + 1, 0) * es), 1)

        //alpha(0) = -1.0
        //beta(0) = 1.0
        // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i1-(i+1), i1-i, 1, &alpha,
        // d_R+(i+1)+(i-i0)*N, N, d_A+i+i*N, N, &beta, d_A+(i+1)+i*N, N)
        JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N, i1 - (i + 1), i1 - i, 1,
          minusOnePtr,
          RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), N,
          AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es), N,
          onePtr,
          AMemPointer.withByteOffset(d_A.linearIndex(i + 1, i) * es), N)

      }
      }

      cfor(iB + 1)(_ < numBlocks, _ + 1) { kB => {
        val k0 = kB * N / numBlocks
        val k1 = (kB + 1) * N / numBlocks

        val numStripes = (i1 - i0) / matrixStripeSize + (if ((i1 - i0) % matrixStripeSize == 0) 0 else 1)

        // for (int iQ=0; iQ<Num_Stripes; iQ++) {
        cfor(0)(_ < numStripes, _ + 1) { iQ => {

          val i_0 = i0 + iQ * (i1 - i0) / numStripes
          val i_1 = i0 + (iQ + 1) * (i1 - i0) / numStripes

          // for (int i=i_0; i<i_1; i++)
          cfor(i_0)(_ < i_1, _ + 1) { i => {
            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i_1-(i+1), k1-k0, 1, &alpha,
            // d_R+(i+1)+(i-i0)*N, N, d_A+i+k0*N, N, &beta, d_A+(i+1)+k0*N, N)
            JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              i_1 - (i + 1), k1 - k0, 1, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(i + 1, i - i0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i, k0) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(i + 1, k0) * es), N)

          }
          }

          // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, i1-i_1, k1-k0, i_1-i_0, &alpha,
          // d_R+i_1+(i_0-i0)*N, N, d_A+i_0+k0*N, N, &beta, d_A+i_1+k0*N, N))
          JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
            i1 - i_1, k1 - k0, i_1 - i_0, minusOnePtr,
            RMemPointer.withByteOffset(d_R.linearIndex(i_1, i_0 - i0) * es), N,
            AMemPointer.withByteOffset(d_A.linearIndex(i_0, k0) * es), N,
            onePtr,
            AMemPointer.withByteOffset(d_A.linearIndex(i_1, k0) * es), N)

        }
        }

      }
      }

      if (iB < numBlocks - 1) {
        // double* vectAii = (double*)malloc((i1-i0)*sizeof(double));
        // double* vectBi	= (double*)malloc((i1-i0)*sizeof(double));
        // cublasGetVector(i1-i0, sizeof(double), d_A+i0+i0*N, N+1, vectAii, 1)
        // puts the i1-i0 elems from diagonal in vectAii:
        JCublas2.cublasGetVector(i1 - i0, es.toInt,
          AMemPointer.withByteOffset(d_A.linearIndex(i0, i0) * es), N + 1,
          jcuda.Pointer.to(vectAii), 1)
        // cublasGetVector(i1-i0, sizeof(double), d_B+i0, 1, vectBi, 1)
        JCublas2.cublasGetVector(i1 - i0, es.toInt,
          BMemPointer.withByteOffset(d_B.linearIndex(i0, 0) * es), 1,
          jcuda.Pointer.to(vectBi), 1)


        // jB.iB
        cfor(iB + 1)(_ < numBlocks, _ + 1) { jB => {
          val j0 = jB * N / numBlocks
          val j1 = (jB + 1) * N / numBlocks

          val numStripes = (i1 - i0) / matrixStripeSize + (if ((i1 - i0) % matrixStripeSize == 0) 0 else 1)

          var iQ = 0
          while (iQ < numStripes) {
            val i_0 = i0 + iQ * (i1 - i0) / numStripes
            val i_1 = i0 + (iQ + 1) * (i1 - i0) / numStripes

            var i = i_0
            while (i < i_1) {

              // cublasDcopy(handle, j1-j0, d_A+j0+i*N, 1, d_R+j0+(i-i0)*N, 1)
              JCublas2.cublasScopy(handle, j1 - j0,
                AMemPointer.withByteOffset(d_A.linearIndex(j0, i) * es), 1,
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1)

              beta(0) = 1.0f / vectAii(i - i0)
              // cublasDscal(handle, j1-j0, &beta, d_R+j0+(i-i0)*N , 1)
              JCublas2.cublasSscal(handle, j1 - j0, jcuda.Pointer.to(beta),
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1)

              beta(0) = -vectBi(i - i0)
              // cublasDaxpy(handle, j1-j0, &beta, d_R+j0+(i-i0)*N, 1, d_B+j0, 1)
              JCublas2.cublasSaxpy(handle, j1 - j0, jcuda.Pointer.to(beta),
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), 1,
                BMemPointer.withByteOffset(d_B.linearIndex(j0, 0) * es), 1)

              // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, i_1-i, 1, &alpha,
              // d_R+j0+(i-i0)*N, N, d_A+i+i*N, N, &beta, d_A+j0+i*N, N)
              JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
                j1 - j0, i_1 - i, 1, minusOnePtr,
                RMemPointer.withByteOffset(d_R.linearIndex(j0, i - i0) * es), N,
                AMemPointer.withByteOffset(d_A.linearIndex(i, i) * es), N,
                onePtr,
                AMemPointer.withByteOffset(d_A.linearIndex(j0, i) * es), N)

              i += 1
            }

            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, i1-i_1, i_1-i_0, &alpha,
            // d_R+j0+(i_0-i0)*N, N, d_A+i_0+i_1*N, N, &beta, d_A+j0+i_1*N, N)
            JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              j1 - j0, i1 - i_1, i_1 - i_0, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(j0, i_0 - i0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i_0, i_1) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(j0, i_1) * es), N)


            iQ += 1
          }

        }
        }

        // jB.kB
        // for (int jB = iB + 1; jB < Num_Blocks; jB++) {
        cfor(iB + 1)(_ < numBlocks, _ + 1) { jB => {

          val j0 = jB * N / numBlocks
          val j1 = (jB + 1) * N / numBlocks

          cfor(iB + 1)(_ < numBlocks, _ + 1) { kB => {
            val k0 = kB * N / numBlocks
            val k1 = (kB + 1) * N / numBlocks

            // cublasDgemm(handle, CUBLAS_OP_N, CUBLAS_OP_N, j1-j0, k1-k0, i1-i0, &alpha,
            // d_R+j0, N, d_A+i0+k0*N, N, &beta, d_A+j0+k0*N, N)
            JCublas2.cublasSgemm(handle, cublasOperation.CUBLAS_OP_N, cublasOperation.CUBLAS_OP_N,
              j1 - j0, k1 - k0, i1 - i0, minusOnePtr,
              RMemPointer.withByteOffset(d_R.linearIndex(j0, 0) * es), N,
              AMemPointer.withByteOffset(d_A.linearIndex(i0, k0) * es), N,
              onePtr,
              AMemPointer.withByteOffset(d_A.linearIndex(j0, k0) * es), N)

          }
          }

        }
        }

      }

    }
    }

    // solve the triangular system:
    JCublas2.cublasStrsv(handle, cublasFillMode.CUBLAS_FILL_MODE_UPPER, cublasOperation.CUBLAS_OP_N,
      cublasDiagType.CUBLAS_DIAG_NON_UNIT, N,
      AMemPointer, d_A.majorSize,
      BMemPointer, 1)

    // the solution vector is in d_B
    d_B
  }

}