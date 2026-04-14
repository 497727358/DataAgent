/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.config.observation;

import com.alibaba.cloud.ai.dataagent.service.llm.LlmTokenUsageService;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;


/**
 * 基于 Micrometer Observation，在每次大模型调用结束后记录 token 用量。
 * <p>
 * 说明：
 * - 由于 Spring AI 的 Observation Context 类型在不同版本/模型里可能不同，这里使用
 *   “KeyValues + 反射”做最大兼容。
 * - sessionId 采用 MDC traceId（若存在）兜底；nodeName 优先使用 model 名称，便于按“每个LLM”聚合。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LlmTokenUsageObservationHandler implements ObservationHandler<ChatModelObservationContext> {

	private final LlmTokenUsageService llmTokenUsageService;

	@Override
	public boolean supportsContext(Observation.Context context) {
		// Spring AI 的 Observation 通常会带低基数 key-values（如 model、provider、operation 等）
		// 这里不做强绑定类型判断，避免版本差异导致 handler 失效。

		return context instanceof ChatModelObservationContext;
	}

	@Override
	public void onStop(ChatModelObservationContext context) {
		try {
			Usage usageInfo = getUsage(context.getResponse());
			if (usageInfo == null) {
				return;
			}
			String modelName = getLowKeyValueByContext(context, "gen_ai.request.model");
			String responseId = getLowKeyValueByContext(context, "gen_ai.response.id");

			llmTokenUsageService.record(responseId, "", modelName, usageInfo.getPromptTokens(), usageInfo.getCompletionTokens(), false);
		}
		catch (Exception e) {
			// 不能影响主流程
			log.debug("Skip recording LLM token usage for observation context: {}");
		}
	}


	private String getLowKeyValueByContext(ChatModelObservationContext context, String key) {
		KeyValue keyValue = context.getLowCardinalityKeyValue(key);
		if (keyValue != null) {
			return keyValue.getValue();
		}
		return "";
	}

	private String getHighKeyValueByContext(ChatModelObservationContext context, String key) {
		KeyValue keyValue = context.getHighCardinalityKeyValue(key);
		if (keyValue != null) {
			return keyValue.getValue();
		}
		return "";
	}

	public static Usage getUsage(ChatResponse chatResponse) {
		if (chatResponse == null) {
			return null;
		}
		var metadata = chatResponse.getMetadata();
		if (metadata == null) {
			return null;
		}
		return metadata.getUsage();
	}

}

