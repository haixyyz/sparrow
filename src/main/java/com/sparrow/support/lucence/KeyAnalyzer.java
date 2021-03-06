/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sparrow.support.lucence;

import java.io.StringReader;
import java.util.*;

import com.sparrow.core.Pair;
import com.sparrow.support.MapValueComparator;
import com.sparrow.utility.RegexUtility;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import com.sparrow.constant.CONFIG;
import com.sparrow.utility.Config;
import com.sparrow.utility.FileUtility;
import com.sparrow.utility.StringUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wltea.analyzer.lucene.IKAnalyzer;

/**
 * @author harry
 */
public class KeyAnalyzer {
    Logger logger = LoggerFactory.getLogger(KeyAnalyzer.class);
    private static KeyAnalyzer keyAnalyzer = new KeyAnalyzer();

    private String lucenceEnableKeywordsPath = null;
    private String lucenceDisableKeywordsPath = null;
    private String lucenceIdfKeywordsPath = null;

    private KeyAnalyzer() {
        lucenceIdfKeywordsPath = Config.getValue(CONFIG.LUCENCE_IDF_KEYWORDS_PATH);
        lucenceEnableKeywordsPath = Config.getValue(CONFIG.LUCENCE_ENABLE_KEYWORDS_PATH);
        lucenceDisableKeywordsPath = Config.getValue(CONFIG.LUCENCE_DISABLE_KEYWORDS_PATH);
    }

    public static KeyAnalyzer getInstance() {
        return keyAnalyzer;
    }

    //某一特定词语的IDF，可以由总文件数目除以包含该词语之文件的数目，再将得到的商取对数得到
    public double getIDF(String keyWord) {
        String line = FileUtility.getInstance().search(lucenceIdfKeywordsPath, keyWord,
            4096, new Comparator<String>() {
                @Override
                public int compare(String currentWord, String keyword) {
                    return currentWord.compareTo(keyword);
                }
            }, 50);
        if (!StringUtility.isNullOrEmpty(line)) {
            Pair<String, String> termPair = Pair.split(line, "\\|");
            return Double.valueOf(termPair.getSecond());
        }
        return 1;
    }

    public boolean isDisable(String keyword) {
        //存数字不分词
        if (RegexUtility.matches(keyword, "^[\\d]*$")) {
            return true;
        }
        String line = FileUtility.getInstance().search(lucenceDisableKeywordsPath, keyword, 100,
            new Comparator<String>() {
                @Override
                public int compare(String currentWord, String keyword) {
                    return currentWord.compareTo(keyword);
                }
            }, 20);
        return !StringUtility.isNullOrEmpty(line);
    }

    public boolean isEnable(String keyword) {
        String line = FileUtility.getInstance().search(lucenceEnableKeywordsPath, keyword, 100,
            new Comparator<String>() {
                @Override
                public int compare(String currentWord, String keyword) {
                    return currentWord.compareTo(keyword);
                }
            }, 20);
        return !StringUtility.isNullOrEmpty(line);
    }

    /**
     * 调用之前要过滤html
     *
     * @param text
     * @return
     */
    public String getKeys(String text) {
        Analyzer analyzer = new IKAnalyzer(true);
        List<String> tagList = new ArrayList<String>();
        try {
            TokenStream tokens = analyzer.reusableTokenStream("",
                new StringReader(text));
            OffsetAttribute offsetAttr = tokens
                .getAttribute(OffsetAttribute.class);
            CharTermAttribute charTermAttr = tokens
                .getAttribute(CharTermAttribute.class);

            Map<String, Integer> termCount = new HashMap<String, Integer>();
            double termSum = 0;
            while (tokens.incrementToken()) {
                char[] charBuf = charTermAttr.buffer();
                String term = new String(charBuf, 0, offsetAttr.endOffset()
                    - offsetAttr.startOffset());

                if (!this.isEnable(term)) {
                    continue;
                }
                // 出现的次数+1
                if (termCount.containsKey(term)) {
                    termCount.put(term, termCount.get(term) + 1);
                } else {
                    termCount.put(term, 1);
                }
                termSum++;
            }

            Map<String, Double> termWeight = new HashMap<String, Double>();
            for (String key : termCount.keySet()) {
                //指的是某一个给定的词语在该文件中出现的次数。这个数字通常会被正规化，以防止它偏向长的文件
                Double tf = termCount.get(key) / termSum;
                Double weight = tf
                    * this.getIDF(key);
                termWeight.put(key, weight);
            }
            // 关键词个数 100=2 1000=3 etc ...
            int keyCount = (int) Math.log10(termSum);
            if (keyCount > 5) {
                keyCount = 5;
            }
            if (keyCount == 0) {
                keyCount = 2;
            }

            Map<String, Double> valueSortedWeight = new TreeMap<String, Double>(new MapValueComparator<String>(termWeight));
            valueSortedWeight.putAll(termWeight);
            for (String term : valueSortedWeight.keySet()) {
                if (tagList.size() >= keyCount) {
                    break;
                }
                tagList.add(term);
            }
        } catch (Exception ex) {
            logger.error("get key error", ex);
        } finally {
            analyzer.close();
        }
        return StringUtility.join(tagList);
    }
}
