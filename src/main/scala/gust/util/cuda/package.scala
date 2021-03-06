package gust.util

import breeze.util.SerializableLogging

import scala.reflect.ClassTag
import org.bridj.{PointerIO, Pointer}
import jcuda.runtime.{cudaStream_t, JCuda}
import jcuda.jcublas.{cublasOperation, JCublas2, cublasHandle}
import jcuda.driver.{JCudaDriver, CUcontext, CUfunction, CUstream}
import jcuda.NativePointerObject
import breeze.macros.arityize

/**
 * TODO
 *
 * @author dlwh
 **/
package object cuda extends SerializableLogging {
  type CuPointer = jcuda.Pointer

  def allocate[V:ClassTag](size: Long): Pointer[V] = {
    val ptr = new CuPointer()
    val tpe = implicitly[ClassTag[V]].runtimeClass
    val io = PointerIO.getInstance[V](tpe)
    val ok: Boolean = hasFreeMemory(size * io.getTargetSize)

    if(!ok) {
      throw new OutOfMemoryError(s"CUDA Memory")//: need $size bytes, but only have ${free(0)}. Tried running the GC to no avail.")
    }

    JCuda.cudaMalloc(ptr, size * io.getTargetSize)
    Pointer.pointerToAddress(nativePtr(ptr), size, DeviceFreeReleaser).as(io)
  }

  def hasFreeMemory(size: Long): Boolean = {
    val free, total = Array[Long](0)
    JCudaDriver.cuMemGetInfo(free, total)
    val ok = (free(0) >= size) || {
      logger.debug("Running GC because we're running low on RAM!")
      System.gc()
      Runtime.getRuntime.runFinalization()
      JCudaDriver.cuMemGetInfo(free, total)
      free(0) >= size
    }
    ok
  }

  def allocateHost[V:ClassTag](size: Long):Pointer[V] = {
    val ptr = new CuPointer()
    val tpe = implicitly[ClassTag[V]].runtimeClass
    val io = PointerIO.getInstance[V](tpe)
    JCuda.cudaMallocHost(ptr, size * io.getTargetSize)
    Pointer.pointerToAddress(nativePtr(ptr), size * io.getTargetSize, HostFreeReleaser).as(io)
  }

  def cuPointerToArray[T](array: Array[T]): jcuda.Pointer = array match {
    case array: Array[Int] => jcuda.Pointer.to(array)
    case array: Array[Byte] => jcuda.Pointer.to(array)
    case array: Array[Long] => jcuda.Pointer.to(array)
    case array: Array[Short] => jcuda.Pointer.to(array)
    case array: Array[Float] => jcuda.Pointer.to(array)
    case array: Array[Double] => jcuda.Pointer.to(array)
    case _ => throw new UnsupportedOperationException("Can't deal with this array type!")
  }

  implicit class enrichBridjPtr[T](val pointer: Pointer[T]) extends AnyVal {
    def toCuPointer = {
      assert(pointer != null)
      fromNativePtr(pointer.getPeer)
    }
  }

  private object DeviceFreeReleaser extends Pointer.Releaser {
    def release(p: Pointer[_]): Unit = {
      val ptr = fromNativePtr(p.getPeer)
      JCuda.cudaFree(ptr)
    }
  }

  private object HostFreeReleaser extends Pointer.Releaser {
    def release(p: Pointer[_]): Unit = {
      val ptr = fromNativePtr(p.getPeer)
      JCuda.cudaFreeHost(ptr)
    }
  }

  private object NoReleaser extends Pointer.Releaser {
    def release(p: Pointer[_]): Unit = {
    }
  }


  def cupointerToPointer[T](pointer: CuPointer, size: Int, io: PointerIO[T]):Pointer[T] = {
    Pointer.pointerToAddress(nativePtr(pointer), size * io.getTargetSize, NoReleaser).as(io)
  }


  def cupointerToPointer[_](pointer: CuPointer):Pointer[_] = {
    Pointer.pointerToAddress(nativePtr(pointer), NoReleaser).offset(stealByteOffset(pointer))
  }

  private def nativePtr(pointer: CuPointer) = {
    val m = classOf[NativePointerObject].getDeclaredMethod("getNativePointer")
    m.setAccessible(true)
    m.invoke(pointer).asInstanceOf[java.lang.Long].longValue()
  }

  private def stealByteOffset(pointer: CuPointer) = {
    val m = classOf[CuPointer].getDeclaredField("byteOffset")
    m.setAccessible(true)
    m.get(pointer).asInstanceOf[java.lang.Long].longValue()
  }

  private def fromNativePtr(peer: Long, offset: Long = 0) = {
    val m = classOf[CuPointer].getDeclaredConstructor(java.lang.Long.TYPE)
    m.setAccessible(true)
    m.newInstance(java.lang.Long.valueOf(peer)).withByteOffset(offset)
  }

  implicit def cudaStreamToCuStream(s: CUstream) = new cudaStream_t(s)

  implicit class richBlas(val blas: cublasHandle) extends AnyVal {
    def withStream[T](stream: cudaStream_t)(block: => T) = blas.synchronized {
      val olds = new cudaStream_t()
      JCublas2.cublasGetStream(blas, olds)
      JCublas2.cublasSetStream(blas, stream)
      val res = block
      JCublas2.cublasSetStream(blas, olds)
      res
    }
  }


  @arityize(10)
  class CuKernel[@arityize.replicate T](module: CuModule, fn: CUfunction) {
    def apply(gridDims: Dim3 = Dim3.default, blockDims: Dim3 = Dim3.default, sharedMemorySize: Int = 0)(@arityize.replicate t: T @arityize.relative(t))(implicit context: CuContext):Unit = {
      CuKernel.invoke(fn, gridDims, blockDims, sharedMemorySize)((t: @arityize.replicate ))
    }
  }
}
