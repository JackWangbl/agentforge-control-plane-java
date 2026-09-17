package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.ModelConfig;

/** 评测和实验都要跑一轮对话，用这个窄接口跟运行时解耦。 */
public interface ChatTurnRunner {
    ChatTurnResult run(Agent agent, ModelConfig model, String message, String sessionId);
}
