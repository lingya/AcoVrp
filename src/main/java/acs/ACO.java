package acs;

import localsearch.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import scala.Serializable;

import static parameter.Parameter.N;
import static parameter.Parameter.appName;
import static parameter.Parameter.master;
import static updatestrategy.BaseUpdateStrategy.*;

import scala.Tuple2;
import scala.collection.Map;
import updatestrategy.BaseUpdateStrategy;
import updatestrategy.UpdateStrategy4Case1;
import updatestrategy.UpdateStrategy4Case2;

import static util.LogUtil.logger;
import static vrp.VRP.*;

import util.DataUtil;
import util.StringUtil;
import parameter.Parameter;
import vrp.Solution;
import vrp.VRP;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Created by ab792 on 2016/12/30.
 */
public class ACO implements Serializable {
    private static final long serialVersionUID = -2542322072011298603L;
    private Ant[] ants; //蚂蚁
    private Integer antNum; //蚂蚁数量
    private Integer ITER_NUM;   //迭代数
    private double[][] pheromone;   //信息素矩阵
    private double bestLen; //最佳长度
    private Solution bestSolution;  //最佳解
    private Ant bestAnt;    //最佳路径的蚂蚁
    private BaseUpdateStrategy baseUpdateStrategy;  //信息素更新策略
    private BaseStretegy stretegy;  //局部搜索策略
    private Solution pre3Solution = null;
    private Solution preNSolution = null;
    private JavaSparkContext ctx;


    public ACO() {
        this.antNum = Parameter.ANT_NUM;
        ITER_NUM = Parameter.ITER_NUM;
        ants = new Ant[antNum];
        baseUpdateStrategy = new UpdateStrategy4Case1();
    }

    public ACO(JavaSparkContext ctx) {
        this.ctx = ctx;
        this.antNum = Parameter.ANT_NUM;
        ITER_NUM = Parameter.ITER_NUM;
        ants = new Ant[antNum];
        baseUpdateStrategy = new UpdateStrategy4Case1();
    }

    public void init(String filePath) {
        if (StringUtil.isNotEmpty(filePath)) {
            try {
                //导入数据
                //importDataFromAVRP(FILE_PATH);
                importDataFromSolomon(filePath);
                System.out.println("fileName---" + fileName);
                //初始化信息素矩阵
                pheromone = new double[clientNum][clientNum];
                for (int i = 0; i < clientNum; i++) {
                    for (int j = 0; j < clientNum; j++) {
                        pheromone[i][j] = Parameter.PHEROMONE_INIT;
                    }
                }
                bestLen = Double.MAX_VALUE;
                //初始化蚂蚁
                initAntCommunity();
            } catch (IOException e) {
                System.err.print("FILE_PATH invalid!");
                e.printStackTrace();
            }

        } else {
            System.err.print("FILE_PATH empty!");
        }
    }

    /**
     * 初始化蚂蚁
     */
    private void initAntCommunity() {
        for (int i = 0; i < antNum; i++) {
            ants[i] = new Ant();
            ants[i].init();
        }
    }

    /**
     * ACO的运行过程
     */
    public void run(BaseStretegy baseStretegy) throws Exception {
        SparkConf conf = new SparkConf().setAppName(appName).setMaster(master);
        JavaSparkContext ctx = new JavaSparkContext(conf);
        int RHOCounter = 0;
        int FINISHCounter = 0;
        //进行ITER_NUM次迭代
        for (int i = 0; i < ITER_NUM; i++) {
            //System.out.println("ITER_NUM:" + i);
            //对于每一只蚂蚁
            JavaRDD<Ant> antsRdds = ctx.parallelize(Arrays.asList(ants));

            /*JavaRDD<Ant> improvedAntsRdds = antsRdds.map(x -> {
                        Ant tempAnt = x.traceRoad(pheromone);
                        baseStretegy.improveSolution(tempAnt.getSolution());
                        return tempAnt;
                    }
            );*/
            JavaRDD<Ant> improvedAntsRdds = antsRdds.map(x -> {
                        Ant tempAnt = x.traceRoad(pheromone);
                        baseStretegy.improveSolution(tempAnt.getSolution());
                        return tempAnt;
                    }
            );
            JavaPairRDD<Double, Ant> pairs = improvedAntsRdds.mapToPair(x -> {
                double len = x.getLength();
                return new Tuple2<Double, Ant>(len, x);
            });
            JavaPairRDD<Double, Ant> sortedPairs = pairs.sortByKey();
            List<Tuple2<Double, Ant>> list = sortedPairs.collect();
            //list.forEach(x-> System.out.println(x._2().getClass() + ": " + x._1()));
            Ant result = list.get(0)._2();
            if (bestSolution == null && bestAnt == null) {
                //logger.info("=========case1==========");
                bestAnt = result;
                bestLen = bestAnt.getLength();
                bestSolution = bestAnt.getSolution();
                //更新最大最小信息素
                updateMaxMinPheromone();
                pre3Solution = bestSolution;
                preNSolution = bestSolution;
            }
            //3.若𝑅的用车 数等于𝑅∗的用车数, 且𝑅的距离/时间费用小于𝑅∗相 应的费用, 或𝑅的用车数小于𝑅∗的用车数时
            else if ((result.getSolution().getTruckNum() == bestSolution.getTruckNum() && DataUtil.less(result.getLength(), bestLen)) || (result.getSolution().getTruckNum() < bestSolution.getTruckNum())) {
                bestAnt = result;
                bestLen = bestAnt.getLength();
                bestSolution = bestAnt.getSolution();
                preNSolution = bestSolution;
                FINISHCounter = 0;
                //更新最大最小信息素
                updateMaxMinPheromone();
            }
            //更新蚂蚁自身的信息素
            result.updatePheromone();
            //更新信息素
            baseUpdateStrategy.updateByAntRule2(pheromone, bestAnt);
            ++RHOCounter;
            ++FINISHCounter;
            //初始化蚁群
            initAntCommunity();
            //如果三代以内，最优解的变化值在3之内，则更新RHO
            if (RHOCounter > 3) {
                RHOCounter = 0;
                if (DataUtil.le(pre3Solution.calCost() - bestSolution.calCost(), 3.0)) {
                    updateRHO();
                }
                pre3Solution = bestSolution;
            }
            if (FINISHCounter >= N) {
                break;
            }
            /*for (int j = 0; j < antNum; j++) {
                //logger.info("第" + j + "只蚂蚁开始");
                ants[j].traceRoad(pheromone);
                //System.out.println("第" + j + "只蚂蚁总路径长度" + ants[j].getLength());
                //System.out.println("第" + j + "只蚂蚁的解"+ants[j].getSolution());
                //改变信息素更新策略
                updatePheromoneBySolution(ants[j]);
                //logger.info("优化前--------------------------------------------------------->" + ants[j].getLength());
                logger.info("=========优化解 begin==========");
                BaseStretegy.improveSolution(ants[j].getSolution());
                logger.info("=========优化解 end==========");
                //System.out.println("优化后的解------------------------->" + ants[j].getLength());
                //3.若𝑅的用车 数等于𝑅∗的用车数, 且𝑅的距离/时间费用小于𝑅∗相 应的费用, 或𝑅的用车数小于𝑅∗的用车数时
                if ((ants[j].getSolution().getTruckNum() == bestSolution.getTruckNum() && DataUtil.less(ants[j].getLength(), bestLen)) || (ants[j].getSolution().getTruckNum() < bestSolution.getTruckNum())) {
                    bestAnt = ants[j];
                    bestLen = bestAnt.getLength();
                    bestSolution = bestAnt.getSolution();
                    //更新最大最小信息素
                    updateMaxMinPheromone();
                }
                //更新蚂蚁自身的信息素
                ants[j].updatePheromone();
                //baseUpdateStrategy.updateByAntRule2(pheromone, bestAnt);
            }*/
            /*++RHOCounter;
            //更新信息素
            baseUpdateStrategy.updateByAntRule1(pheromone, ants, bestAnt);
            //初始化蚁群
            initAntCommunity();
            //如果三代以内，最优解的变化值在3之内，则更新RHO
            if (RHOCounter > 3) {
                RHOCounter = 0;
                if (DataUtil.le(pre3Solution.calCost() - bestSolution.calCost(), 3.0)) {
                    updateRHO();
                }
                pre3Solution = bestSolution;
            }*/
        }
        //打印最佳结果
        printOptimal();
    }

    /**
     * 通过得出的解更新信息素
     *
     * @param ant
     */
    private void updatePheromoneBySolution(Ant ant) {
        if (bestSolution == null && bestAnt == null) {
            //logger.info("=========case1==========");
            bestAnt = ant;
            bestLen = bestAnt.getLength();
            bestSolution = bestAnt.getSolution();
            //更新最大最小信息素
            updateMaxMinPheromone();
            pre3Solution = bestSolution;
        }
        //1.若𝑅的用车数大于𝑅∗的 用车数, 则将𝑅中所有边上的信息素进行大量蒸发
        else if (ant.getSolution().getTruckNum() > bestSolution.getTruckNum()) {
            //logger.info("=========case2==========");
            setBaseUpdateStrategy(new UpdateStrategy4Case1());
            baseUpdateStrategy.updatePheBySolution(pheromone, ant.getSolution());
        }
        //2.若𝑅的用车数等 于𝑅∗的用车数, 但𝑅的距离/时间费用大于等于𝑅∗相 应的费用, 则将𝑅中所有边上的信息素进行少量蒸发
        else if (ant.getSolution().getTruckNum() == bestSolution.getTruckNum() && DataUtil.ge(ant.getLength(), bestLen)) {
            //logger.info("=========case3==========");
            setBaseUpdateStrategy(new UpdateStrategy4Case2());
            baseUpdateStrategy.updatePheBySolution(pheromone, ant.getSolution());
        }
    }

    private void updateRHO() {
        //System.out.println("ACO.updateRHO");
        Parameter.RHO *= 1.05;
        Parameter.RHO = DataUtil.ge(Parameter.RHO, 1.0) ? 0.99 : Parameter.RHO;
        //System.out.println("RHO--->" + Parameter.RHO);
    }

    /**
     * 更新最大最小信息素
     */
    private void updateMaxMinPheromone() {
        Parameter.PHEROMONE_MAX = calPheromoneMax(bestLen, clientNum);
        Parameter.PHEROMONE_MIN = calPheromoneMin(Parameter.PHEROMONE_MAX);
    }

    /**
     * 计算最小信息素
     * 𝜏min = 𝜏max/20
     *
     * @param pheromoneMax
     * @return
     */
    private Double calPheromoneMin(Double pheromoneMax) {
        return pheromoneMax / Parameter.pheSpan;
    }

    /**
     * 计算最大信息素
     * 𝜏max = 𝐶/(𝐿(𝑅∗)×𝑛×(1−𝜌))
     *
     * @param bestLen
     * @param clientNum
     * @return
     */
    private Double calPheromoneMax(double bestLen, Integer clientNum) {
        return Parameter.C / bestLen * (clientNum - 1) * (1 - Parameter.RHO);
    }


    /**
     * 打印最佳结果
     */
    private void printOptimal() {
        System.out.println("The optimal length is: " + bestLen);
        System.out.println("The optimal tour is: ");
        System.out.println(bestSolution);
        System.out.println("The value of pheromone:");
        for (int i = 0; i < pheromone.length; i++) {
            for (int j = 0; j < pheromone[i].length; j++) {
                System.out.print(pheromone[i][j] + "\t");
            }
            System.out.print("\n");
        }
    }

    public void setBaseUpdateStrategy(BaseUpdateStrategy baseUpdateStrategy) {
        this.baseUpdateStrategy = baseUpdateStrategy;
    }

    public void setStretegy(BaseStretegy stretegy) {
        this.stretegy = stretegy;
    }
}
