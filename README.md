# FutureTask
FutureTask源码解析

一、FutureTask是什么？
-----
<br>FutureTask是**可取消**的**异步**的计算任务，它可以通过线程池和Thread对象执行，一般来说FutureTask用于耗时的计算。

二、FutureTask继承图
----

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
*  *FutureTask的变量*

