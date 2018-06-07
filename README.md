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
*  *FutureTask的变量参数*
<br>`private Object outcome;`
<br>任务返回值，正常返回时是泛型指定对象，任务异常时是Throwable对象，它在state=COMPLETING阶段完成赋值操作。
<br>`private volatile Thread runner;`
<br>当前执行任务线程，run()方法开始时进行判断和赋值，保证同一时刻只有一个线程执行FutureTask，并且FutureTask.run()只能执行一次。
<br>`private volatile WaitNode waiters;`
<br>阻塞队列头节点，每个节点存储调用FutureTask.get()方法，且采用LockSupport.park()阻塞的线程。在任务对outcome完成赋值后，调用finishCompletion()唤醒所有阻塞线程。

*  *FutureTask的两个构造方法*
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
 
 * **
