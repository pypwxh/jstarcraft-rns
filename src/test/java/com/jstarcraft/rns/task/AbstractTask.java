package com.jstarcraft.rns.task;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jstarcraft.ai.data.DataInstance;
import com.jstarcraft.ai.data.DataModule;
import com.jstarcraft.ai.data.DataSpace;
import com.jstarcraft.ai.data.converter.ArffConverter;
import com.jstarcraft.ai.data.converter.CsvConverter;
import com.jstarcraft.ai.data.converter.DataConverter;
import com.jstarcraft.ai.data.module.ReferenceModule;
import com.jstarcraft.ai.data.processor.DataSplitter;
import com.jstarcraft.ai.environment.EnvironmentContext;
import com.jstarcraft.ai.environment.EnvironmentFactory;
import com.jstarcraft.ai.evaluate.Evaluator;
import com.jstarcraft.ai.math.structure.matrix.HashMatrix;
import com.jstarcraft.ai.math.structure.matrix.SparseMatrix;
import com.jstarcraft.core.common.conversion.json.JsonUtility;
import com.jstarcraft.core.common.reflection.ReflectionUtility;
import com.jstarcraft.core.common.reflection.TypeUtility;
import com.jstarcraft.core.utility.Integer2FloatKeyValue;
import com.jstarcraft.core.utility.KeyValue;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.core.utility.StringUtility;
import com.jstarcraft.rns.configure.Configurator;
import com.jstarcraft.rns.data.processor.QualityFeatureDataSplitter;
import com.jstarcraft.rns.data.separator.DataSeparator;
import com.jstarcraft.rns.data.separator.GivenDataSeparator;
import com.jstarcraft.rns.data.separator.GivenNumberSeparator;
import com.jstarcraft.rns.data.separator.KFoldCrossValidationSeparator;
import com.jstarcraft.rns.data.separator.LeaveOneCrossValidationSeparator;
import com.jstarcraft.rns.data.separator.RandomSeparator;
import com.jstarcraft.rns.data.separator.RatioSeparator;
import com.jstarcraft.rns.recommend.Recommender;
import com.jstarcraft.rns.recommend.exception.RecommendException;

import it.unimi.dsi.fastutil.ints.Int2FloatRBTreeMap;

/**
 * 抽象任务
 * 
 * @author Birdy
 *
 * @param <T>
 */
public abstract class AbstractTask<L, R> {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected Configurator configuration;

    protected String userField, itemField;

    protected int userDimension, itemDimension, userSize, itemSize;

//    protected int[] trainPaginations, trainPositions, testPaginations, testPositions;

    protected ReferenceModule[] trainModules, testModules;

    protected DataModule dataMarker, trainMarker, testMarker;

    protected Recommender recommender;

    protected AbstractTask(Recommender recommender, Configurator configuration) {
        this.configuration = configuration;
        Long seed = configuration.getLong("recommender.random.seed");
        if (seed != null) {
            RandomUtility.setSeed(seed);
        }
        this.recommender = recommender;
    }

    protected AbstractTask(Class<? extends Recommender> clazz, Configurator configuration) {
        this.configuration = configuration;
        Long seed = configuration.getLong("recommender.random.seed");
        if (seed != null) {
            RandomUtility.setSeed(seed);
        }
        this.recommender = (Recommender) ReflectionUtility.getInstance(clazz);
    }

    protected abstract Collection<Evaluator> getEvaluators(SparseMatrix featureMatrix);

    protected abstract L check(int userIndex);

    protected abstract R recommend(Recommender recommender, int userIndex);

    private ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private Map<Class<? extends Evaluator>, Integer2FloatKeyValue> evaluate(Collection<Evaluator> evaluators, Recommender recommender) {
        Map<Class<? extends Evaluator>, Integer2FloatKeyValue[]> values = new HashMap<>();
        for (Evaluator evaluator : evaluators) {
            values.put(evaluator.getClass(), new Integer2FloatKeyValue[userSize]);
        }
        // 按照用户切割任务.
        CountDownLatch latch = new CountDownLatch(userSize);
        for (int userIndex = 0; userIndex < userSize; userIndex++) {
            int index = userIndex;
            executor.submit(() -> {
                try {
                    ReferenceModule module = testModules[index];
                    if (module.getSize() == 0) {
                        return;
                    }
                    // 校验集合
                    L checkCollection = check(index);
                    // 推荐列表
                    R recommendList = recommend(recommender, index);
                    // 测量列表
                    for (Evaluator<L, R> evaluator : evaluators) {
                        Integer2FloatKeyValue[] measures = values.get(evaluator.getClass());
                        Integer2FloatKeyValue measure = evaluator.evaluate(checkCollection, recommendList);
                        measures[index] = measure;
                    }
                } catch (Exception exception) {
                    logger.error("任务异常", exception);
                } finally {
                    latch.countDown();
                }

            });
        }
        try {
            latch.await();
        } catch (Exception exception) {
            throw new RecommendException(exception);
        }

        Map<Class<? extends Evaluator>, Integer2FloatKeyValue> measures = new HashMap<>();
        for (Entry<Class<? extends Evaluator>, Integer2FloatKeyValue[]> term : values.entrySet()) {
            Integer2FloatKeyValue measure = new Integer2FloatKeyValue(0, 0F);
            // if (term.getKey() == RecallEvaluator.class) {
            // for (KeyValue<Integer, Double> element : term.getValue()) {
            // if (element == null) {
            // continue;
            // }
            // System.out.println(element.getKey() + " " + element.getValue());
            // }
            // }
            for (Integer2FloatKeyValue element : term.getValue()) {
                if (element == null) {
                    continue;
                }
                measure.setKey(measure.getKey() + element.getKey());
                measure.setValue(measure.getValue() + element.getValue());
            }
            measures.put(term.getKey(), measure);
        }
        return measures;
    }

    public Map<String, Float> execute() throws Exception {
        // TODO 数据属性部分
        // 离散属性
        Type dicreteConfiguration = TypeUtility.parameterize(HashMap.class, String.class, Class.class);
        Map<String, Class<?>> dicreteDifinitions = JsonUtility.string2Object(configuration.getString("data.attributes.dicrete"), dicreteConfiguration);
        // 连续属性
        Type continuousConfiguration = TypeUtility.parameterize(HashMap.class, String.class, Class.class);
        Map<String, Class<?>> continuousDifinitions = JsonUtility.string2Object(configuration.getString("data.attributes.continuous"), continuousConfiguration);

        // 数据空间部分
        DataSpace space = new DataSpace(dicreteDifinitions, continuousDifinitions);

        // TODO 数据模型部分
        ModuleConfigurer[] moduleConfigurers = JsonUtility.string2Object(configuration.getString("data.modules"), ModuleConfigurer[].class);
        for (ModuleConfigurer moduleConfigurer : moduleConfigurers) {
            space.makeDenseModule(moduleConfigurer.getName(), moduleConfigurer.getConfiguration(), 1000000000);
        }

        // TODO 数据转换器部分
        Type convertorConfiguration = TypeUtility.parameterize(LinkedHashMap.class, String.class, TypeUtility.parameterize(KeyValue.class, String.class, HashMap.class));
        ConverterConfigurer[] converterConfigurers = JsonUtility.string2Object(configuration.getString("data.converters"), ConverterConfigurer[].class);
        for (ConverterConfigurer converterConfigurer : converterConfigurers) {
            String name = converterConfigurer.getName();
            String type = converterConfigurer.getType();
            String path = converterConfigurer.getPath();
            DataConverter convertor = null;
            switch (type) {
            case "arff": {
                convertor = ReflectionUtility.getInstance(ArffConverter.class, space.getQualityAttributes(), space.getQuantityAttributes());
                break;
            }
            case "csv": {
                convertor = ReflectionUtility.getInstance(CsvConverter.class, configuration.getCharacter("data.splitter.delimiter", ' '), space.getQualityAttributes(), space.getQuantityAttributes());
                break;
            }
            default: {
                throw new RecommendException("不支持的转换格式");
            }
            }
            File file = new File(path);
            DataModule module = space.getModule(name);
            try (InputStream stream = new FileInputStream(file)) {
                convertor.convert(module, stream, converterConfigurer.getQualityMarkOrder(), converterConfigurer.getQuantityMarkOrder(), converterConfigurer.getWeightOrder());
            }
        }

        // TODO 数据切割器部分
        SplitterConfigurer splitterConfigurer = JsonUtility.string2Object(configuration.getString("data.splitter"), SplitterConfigurer.class);
        DataModule model = space.getModule(splitterConfigurer.getName());
        DataSeparator separator;
        switch (splitterConfigurer.getType()) {
        case "kcv": {
            int size = configuration.getInteger("data.splitter.kcv.number", 1);
            separator = new KFoldCrossValidationSeparator(model, size);
            break;
        }
        case "loocv": {
            separator = new LeaveOneCrossValidationSeparator(space, model, splitterConfigurer.getMatchField(), splitterConfigurer.getSortField());
            break;
        }
        case "testset": {
            int threshold = configuration.getInteger("data.splitter.threshold");
            separator = new GivenDataSeparator(model, threshold);
            break;
        }
        case "givenn": {
            int number = configuration.getInteger("data.splitter.given-number.number");
            separator = new GivenNumberSeparator(space, model, splitterConfigurer.getMatchField(), splitterConfigurer.getSortField(), number);
            break;
        }
        case "random": {
            float random = configuration.getFloat("data.splitter.random.value", 0.8F);
            separator = new RandomSeparator(space, model, splitterConfigurer.getMatchField(), random);
            break;
        }
        case "ratio": {
            double ratio = configuration.getDouble("data.splitter.ratio.value", 0.8D);
            separator = new RatioSeparator(space, model, splitterConfigurer.getMatchField(), splitterConfigurer.getSortField(), ratio);
            break;
        }
        default: {
            throw new RecommendException("不支持的划分类型");
        }
        }

        // 评估部分
        userField = configuration.getString("data.model.fields.user", "user");
        itemField = configuration.getString("data.model.fields.item", "item");

        Double binarize = configuration.getDouble("data.convert.binarize.threshold");
        Map<String, Float> measures = new TreeMap<>();

        EnvironmentContext context = EnvironmentFactory.getContext();
        Future<?> task = context.doTask(() -> {
            try {
                for (int index = 0; index < separator.getSize(); index++) {
                    trainMarker = separator.getTrainReference(index);
                    testMarker = separator.getTestReference(index);
                    dataMarker = model;

                    userDimension = model.getQualityInner(userField);
                    itemDimension = model.getQualityInner(itemField);
                    userSize = space.getQualityAttribute(userField).getSize();
                    itemSize = space.getQualityAttribute(itemField).getSize();

                    DataSplitter splitter = new QualityFeatureDataSplitter(userDimension);
                    trainModules = splitter.split(trainMarker, userSize);
                    testModules = splitter.split(testMarker, userSize);

                    HashMatrix dataTable = new HashMatrix(true, userSize, itemSize, new Int2FloatRBTreeMap());
                    for (DataInstance instance : dataMarker) {
                        int rowIndex = instance.getQualityFeature(userDimension);
                        int columnIndex = instance.getQualityFeature(itemDimension);
                        // TODO 处理冲突
                        dataTable.setValue(rowIndex, columnIndex, instance.getQuantityMark());
                    }
                    SparseMatrix featureMatrix = SparseMatrix.valueOf(userSize, itemSize, dataTable);

                    recommender.prepare(configuration, trainMarker, space);
                    recommender.practice();
                    for (Entry<Class<? extends Evaluator>, Integer2FloatKeyValue> measure : evaluate(getEvaluators(featureMatrix), recommender).entrySet()) {
                        Float value = measure.getValue().getValue() / measure.getValue().getKey();
                        measures.put(measure.getKey().getSimpleName(), value);
                    }
                }
            } catch (Exception exception) {
                logger.error("任务异常", exception);
            }
        });
        task.get();

        for (Entry<String, Float> term : measures.entrySet()) {
            term.setValue(term.getValue() / separator.getSize());
            if (logger.isInfoEnabled()) {
                logger.info(StringUtility.format("measure of {} is {}", term.getKey(), term.getValue()));
            }
        }
        return measures;
    }

    public Recommender getRecommender() {
        return recommender;
    }

    public DataModule getDataMarker() {
        return dataMarker;
    }

}