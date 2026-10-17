/*
 * AI SQL for DBeaver — доступ к ИИ-движку DBeaver для правки выделения.
 *
 * Все обращения к внутренним API DBeaver (model.ai) изолированы в этом
 * классе: при обновлении DBeaver риски сосредоточены здесь. Используется
 * штатный профиль ИИ из настроек DBeaver (тот же, что у ИИ-чата) — модель,
 * URL и ключ настраивать заново не нужно.
 *
 * Ответ запрашивается потоком (дельты дописываются к накопленному тексту),
 * поэтому «призрачный» текст печатается по мере генерации. Если движок
 * не поддержал поток — используется обычный запрос целиком.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.edit;

import com.pka.dbeaver.aisql.ui.AisqlUIPlugin;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AISettings;
import org.jkiss.dbeaver.model.ai.AIUsage;
import org.jkiss.dbeaver.model.ai.engine.AIEngine;
import org.jkiss.dbeaver.model.ai.engine.AIEngineRequest;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponse;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;
import org.jkiss.dbeaver.model.ai.registry.AISettingsManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.List;

/**
 * Тонкая обёртка над ИИ-движком DBeaver: «системная инструкция + фрагмент» →
 * текст-замена. Блокирующий вызов (запускается в фоновом Job).
 */
public final class AiEditClient {

    /** Потоковая выдача текста модели. */
    public interface Sink {

        /** Очередной накопленный (полный) текст ответа. */
        void onDelta(String accumulated);

        /** Генерация завершена, {@code fullText} — ответ целиком. */
        void onDone(String fullText);
    }

    private AiEditClient() {
    }

    /** Отправляет запрос выбранной в DBeaver модели. */
    public static void generate(DBRProgressMonitor monitor, String systemPrompt, String userMessage, Sink sink)
        throws DBException {
        AIConfigurationProfile profile = defaultProfile();
        AIEngine<?> engine = profile.getEngineDescriptor().createEngineInstance(profile);
        try {
            AIEngineRequest request = new AIEngineRequest(List.of(
                AIMessage.systemMessage(systemPrompt),
                AIMessage.userMessage(userMessage)));
            StringBuilder accumulated = new StringBuilder();
            Throwable[] streamError = new Throwable[1];
            boolean[] received = new boolean[1];
            try {
                engine.requestCompletionStream(monitor, request, new AIEngineResponseConsumer() {
                    @Override
                    public void nextChunk(AIEngineResponseChunk chunk) {
                        if (chunk.getFunctionCall() != null) {
                            return;
                        }
                        for (String part : chunk.getChoices()) {
                            if (part == null || part.isEmpty()) {
                                continue;
                            }
                            accumulated.append(part);
                            received[0] = true;
                            sink.onDelta(accumulated.toString());
                        }
                    }

                    @Override
                    public void error(Throwable throwable) {
                        streamError[0] = throwable;
                    }

                    @Override
                    public void completeBlock() {
                    }

                    @Override
                    public void usage(AIUsage usage) {
                    }

                    @Override
                    public void systemPromptLength(int length) {
                    }

                    @Override
                    public void warning(String message) {
                        AisqlUIPlugin.log().debug("AI edit warning: " + message);
                    }
                });
            } catch (DBException streamFailure) {
                if (received[0] || monitor.isCanceled()) {
                    throw streamFailure;
                }
                // Ошибка до первого чанка: возможно, движок не умеет стрим —
                // ниже будет обычный запрос.
                AisqlUIPlugin.log().debug(
                    "Потоковая генерация недоступна, повтор обычным запросом", streamFailure);
            }
            if (received[0]) {
                if (streamError[0] != null) {
                    throw new DBException(
                        "Ошибка генерации: " + describe(streamError[0]), streamError[0]);
                }
                String text = accumulated.toString().trim();
                if (text.isEmpty()) {
                    throw new DBException("Модель вернула пустой ответ.");
                }
                sink.onDelta(text);
                sink.onDone(text);
                return;
            }
            // Поток не дал текста (движок без стрима или ошибка до первого
            // чанка) — запрашиваем ответ целиком.
            String text = requestOnce(engine, monitor, request);
            sink.onDelta(text);
            sink.onDone(text);
        } finally {
            try {
                engine.close();
            } catch (DBException e) {
                AisqlUIPlugin.log().debug("Не удалось закрыть ИИ-движок", e);
            }
        }
    }

    private static AIConfigurationProfile defaultProfile() throws DBException {
        AISettings settings = AISettingsManager.getStaticSettings();
        if (settings == null) {
            throw new DBException("В DBeaver не выбран профиль ИИ. "
                + "Настройте модель в Preferences → AI (как для ИИ-чата) и повторите.");
        }
        if (settings.isAiDisabled()) {
            throw new DBException("ИИ-интеграция DBeaver отключена "
                + "(Preferences → AI). Включите её и повторите.");
        }
        // Токен профиля DBeaver хранит в защищённом хранилище и подтягивает
        // лениво; штатный ИИ-чат делает это перед каждым запросом
        // (AIAssistantImpl.checkAiEnablement). Без этого прямой движок
        // падает с «OpenAI API token is not set», если чат ещё не
        // запускался в текущей сессии DBeaver.
        settings.resolveSecrets();
        AIConfigurationProfile profile = settings.getDefaultConfigurationOrNull();
        if (profile == null) {
            throw new DBException("В DBeaver не выбран профиль ИИ. "
                + "Настройте модель в Preferences → AI (как для ИИ-чата) и повторите.");
        }
        return profile;
    }

    private static String requestOnce(AIEngine<?> engine, DBRProgressMonitor monitor, AIEngineRequest request)
        throws DBException {
        AIEngineResponse response = engine.requestCompletion(monitor, request);
        List<String> variants = response == null ? null : response.getVariants();
        String text = variants == null || variants.isEmpty() ? null : variants.get(0);
        if (text == null || text.trim().isEmpty()) {
            throw new DBException("Модель вернула пустой ответ.");
        }
        return text;
    }

    private static String describe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return error == null ? "неизвестная ошибка" : error.getClass().getSimpleName();
        }
        return message;
    }
}
