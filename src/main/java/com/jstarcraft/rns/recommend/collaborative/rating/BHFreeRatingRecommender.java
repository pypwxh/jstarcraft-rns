package com.jstarcraft.rns.recommend.collaborative.rating;

import java.util.Map.Entry;

import com.jstarcraft.ai.data.DataInstance;
import com.jstarcraft.rns.recommend.collaborative.BHFreeRecommender;

/**
 * 
 * BH Free推荐器
 * 
 * <pre>
 * Balancing Prediction and Recommendation Accuracy: Hierarchical Latent Factors for Preference Data
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class BHFreeRatingRecommender extends BHFreeRecommender {

    @Override
    public void predict(DataInstance instance) {
        int userIndex = instance.getQualityFeature(userDimension);
        int itemIndex = instance.getQualityFeature(itemDimension);
        float value = 0F, probabilities = 0F;
        for (Entry<Float, Integer> entry : scoreIndexes.entrySet()) {
            float score = entry.getKey();
            float probability = 0F;
            for (int userTopic = 0; userTopic < userTopicSize; userTopic++) {
                for (int itemTopic = 0; itemTopic < itemTopicSize; itemTopic++) {
                    probability += user2TopicProbabilities.getValue(userIndex, userTopic) * userTopic2ItemTopicProbabilities.getValue(userTopic, itemTopic) * userTopic2ItemTopicScoreProbabilities[userTopic][itemTopic][entry.getValue()];
                }
            }
            value += score * probability;
            probabilities += probability;
        }
        instance.setQuantityMark(value / probabilities);
    }

}
