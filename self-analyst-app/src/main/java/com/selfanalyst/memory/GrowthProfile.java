package com.selfanalyst.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GrowthProfile {

    public record Goal(
            String id,
            String description,
            String metric,
            double baseline,
            double target,
            @JsonSerialize(using = LocalDateSerializer.class)
            @JsonDeserialize(using = LocalDateDeserializer.class)
            LocalDate setAt,
            boolean active) {
        public static Goal create(String description, String metric,
                                   double baseline, double target) {
            return new Goal(
                    java.util.UUID.randomUUID().toString().substring(0, 8),
                    description, metric, baseline, target,
                    LocalDate.now(), true);
        }
    }

    public record KnownPattern(
            String description,
            String evidence,
            @JsonSerialize(using = LocalDateSerializer.class)
            @JsonDeserialize(using = LocalDateDeserializer.class)
            LocalDate confirmedAt,
            int confidence) {
    }

    public record ImprovementLog(
            String goalId,
            String action,
            String outcome,
            @JsonSerialize(using = LocalDateSerializer.class)
            @JsonDeserialize(using = LocalDateDeserializer.class)
            LocalDate observedAt) {
    }

    private List<Goal> goals = new ArrayList<>();
    private List<KnownPattern> patterns = new ArrayList<>();
    private List<ImprovementLog> logs = new ArrayList<>();

    public GrowthProfile() {}

    public List<Goal> getGoals() { return goals; }
    public void setGoals(List<Goal> goals) { this.goals = goals; }
    public List<KnownPattern> getPatterns() { return patterns; }
    public void setPatterns(List<KnownPattern> patterns) { this.patterns = patterns; }
    public List<ImprovementLog> getLogs() { return logs; }
    public void setLogs(List<ImprovementLog> logs) { this.logs = logs; }

    public String buildContextSummary() {
        StringBuilder sb = new StringBuilder();

        List<Goal> active = goals.stream().filter(Goal::active).toList();
        if (!active.isEmpty()) {
            sb.append("## 活跃目标\n");
            for (Goal g : active) {
                sb.append("- ").append(g.description())
                  .append("（基线: ").append(g.baseline())
                  .append("，目标: ").append(g.target())
                  .append("，设立于 ").append(g.setAt()).append("）\n");
            }
            sb.append("\n");
        }

        if (!patterns.isEmpty()) {
            sb.append("## 已确认的行为模式\n");
            for (KnownPattern p : patterns) {
                sb.append("- ").append(p.description())
                  .append("（置信度: ").append(p.confidence()).append("/10）\n");
            }
            sb.append("\n");
        }

        if (!logs.isEmpty()) {
            sb.append("## 最近的改进记录\n");
            var recent = logs.stream()
                    .sorted((a, b) -> b.observedAt().compareTo(a.observedAt()))
                    .limit(5)
                    .toList();
            for (ImprovementLog log : recent) {
                sb.append("- ").append(log.observedAt()).append(": ")
                  .append(log.action()).append(" → ").append(log.outcome()).append("\n");
            }
            sb.append("\n");
        }

        if (sb.isEmpty()) {
            sb.append("暂无已存储的目标、模式或改进记录。这是首次使用。\n");
        }

        return sb.toString();
    }
}
