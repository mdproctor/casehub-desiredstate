package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public class GraphRuleCycleException extends RuntimeException {
    private final List<String> ruleNames;
    private final List<String> cyclePath;

    public GraphRuleCycleException(List<String> ruleNames, List<String> cyclePath) {
        super("Graph rules introduced a cycle: "
              + String.join(" → ", cyclePath)
              + ". Rules: " + String.join(", ", ruleNames));
        this.ruleNames = ruleNames;
        this.cyclePath = cyclePath;
    }

    public List<String> getRuleNames() {return ruleNames;}

    public List<String> getCyclePath() {return cyclePath;}
}
