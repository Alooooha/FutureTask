# FutureTask
FutureTask源码解析

一、FutureTask是什么？
-----
<br>FutureTask是**可取消**的**异步**的计算任务，它可以通过线程池和Thread对象执行，一般来说FutureTask用于耗时的计算。

二、FutureTask继承图
----
![](https://github.com/Alooooha/FutureTask/blob/master/img/img1.png)

三、FutureTask源码
----
*  *FutureTask的七种状态*
 
|状态 (state)|值      |描述                    |        
|------------|-------|-------------------------|
|NEW         |   0   | 任务执行阶段，结果赋值前  |
|COMPLETING  |   1   | 结果赋值阶段             |
|NORMAL      |   2   | 任务执行完毕             |
|EXCEPTIONAL |   3   | 任务执行时发生异常       |
|CANCELLED   |   4   | 任务被取消               |
|INTERRUPTING|   5   | 设置中断变量阶段         |
|INRTERRUPTED|   6   | 任务中断                |

**可能出现的状态变化
 <br>NEW -> COMPLETING -> NORMAL 
 <br>NEW -> COMPLETING -> EXCEPTION 
 <br>NEW ->CANCELLED 
 <br>NEW -> INITERRUPTING -> INTERRUPTED**
*  *FutureTask的**变量参数***
<br>`private Object outcome;`
<br>任务返回值，正常返回时是泛型指定对象，任务异常时是Throwable对象，它在state=COMPLETING阶段完成赋值操作。
<br>`private volatile Thread runner;`
<br>当前执行任务线程，run()方法开始时进行判断和赋值，保证同一时刻只有一个线程执行FutureTask，并且FutureTask.run()只能执行一次。
<br>`private volatile WaitNode waiters;`
<br>阻塞队列头节点，每个节点存储调用FutureTask.get()方法，且采用LockSupport.park()阻塞的线程。在任务对outcome完成赋值后，调用finishCompletion()唤醒所有阻塞线程。

*  *FutureTask的**两个构造方法***
<br>`FtureTask(Callable<V> callable)`
<br>FutureTask.run()中调用callable.call()。
<br>`FutureTask(Runnable runnable, V result)`
<br>调用Executors.callable(runnable, result)，返回new RunnableAdapter<T>(task, result)，RunnableAdapter源码：
```
  static final class RunnableAdapter<T> implements Callable<T> {
        final Runnable task;
        final T result;
        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }
        public T call() {
            task.run();
            return result;
        }
    }
```
 
 * *FutureFask的**静态参数***
<br>`private static final long stateOffset;`
<br>state参数在内存中对象的偏移量
<br>`private static final long runnerOffset;`
<br>runner参数在内存中对象的偏移量
<br>`private static final long waitersOffset;`
<br>waiter参数在内存中对象的偏移量
<br>**赋值**：
```
  Class<?> k = FutureTask.class;
		stateOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
		runnerOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
		waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
```
<br>`private static final sun.misc.Unsafe ` **UNSAFE**;
<br>UNSAFE类提供高效并且线程安全方式操作变量，直接和内存数据打交道，但是分配的内存需要手动释放。由于UNSAFE类只提供给JVM信任的启动类加载器所使用，这里采用反射获取UNSAFE对象。
```
/**
* 通过反射的方式来访问Unsafe类中的theUnsafe静态成员变量，该theUnsafe静态成员变量在Unsafe第一次使用时就已经初始化。
*/
private static sun.misc.Unsafe getUnsafe() {
		  Class<?> unsafeClass = sun.misc.Unsafe.class;
		  try {
			   for (Field f : unsafeClass.getDeclaredFields()) {
				    if ("theUnsafe".equals(f.getName())) {
					   f.setAccessible(true);
					   return (sun.misc.Unsafe)f.get(null);
				  }
			 }
		  }catch(Exception e) {
			    e.printStackTrace();
		  }
		    return null;
}
```
* *FutureTask的**静态内部类***
<br>WaitNode:用于存储当前因调用get()方法而阻塞的线程。
```
	/**
	 *将等待线程封装成节点，形成等待队列 
	 * @author BeiwEi
	 */
	static final class WaitNode {
		  volatile Thread thread;
		  volatile WaitNode next;
		  WaitNode() { 
       thread = Thread.currentThread();
    }
	}
```
* ***LockSupport***
<br>LockSupport是用来创建锁和其他同步类的基本**线程阻塞**原语，park()和unpark()方法实现阻塞线程和解除线程阻塞，LockSupport和每个使用它的线程都与一个许可(permit)关联。permit相当于1，0的开关，默认是0，调用一次unpark就加1变成1，调用一次park会消费permit, 也就是将1变成0，同时park立即返回。再次调用park会变成block（因为permit为0了，会阻塞在这里，直到permit变为1）, 这时调用unpark会把permit置为1。每个线程都有一个相关的permit, permit最多只有一个，重复调用unpark也不会积累。
