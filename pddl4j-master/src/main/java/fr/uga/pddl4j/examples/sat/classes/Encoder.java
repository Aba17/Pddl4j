package fr.uga.pddl4j.examples.sat.classes;

import fr.uga.pddl4j.problem.Fluent;
import fr.uga.pddl4j.problem.InitialState;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.operator.Action;
import fr.uga.pddl4j.problem.operator.ConditionalEffect;
import fr.uga.pddl4j.problem.operator.Effect;
import fr.uga.pddl4j.util.BitVector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Encoder {
    private static final Logger LOGGER = LogManager.getLogger(Encoder.class.getName());

    private final Problem problem;
    private final int horizon;
    private List<int[]> clauses;
    private Map<String, Integer> varMap; // From variable names to SAT variable idx
    private int varCounter;
    private Map<Integer, String> rVarMap; // The reverse : from SAT variable idx to names

    private Map<String, Integer> effectTriggerAuxVars; // Auxiliary variables for conditional effect triggers
    private List<Action> uActions; // To save hete the actions not duplicated (unique Actions)

    public Encoder(Problem problem, int horizon) {
        this.problem = problem;
        this.horizon = horizon;
        this.clauses = new ArrayList<>();
        this.varMap = new HashMap<>();
        this.rVarMap = new HashMap<>();
        this.effectTriggerAuxVars = new HashMap<>();
        this.varCounter = 1;
        // Filter duplicate actions by their name
        this.uActions = problem.getActions().stream()
            .filter(distinctByKey(this::getActionName))
            .collect(Collectors.toList());

    }

    // Method to get unique elements based on a specific key
    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }


    private String getFluentName(Fluent fluent) {
        return fluent.toString().toLowerCase();
    }

    public String getActionName(Action action) {
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        sb.append(action.getName().toLowerCase());
        for (int i = 0; i < action.arity(); i++) {
            int constantIndex = action.getValueOfParameter(i);
            if (constantIndex >= 0 && constantIndex < problem.getConstantSymbols().size()) {
                sb.append(" ");
                sb.append(problem.getConstantSymbols().get(constantIndex).toLowerCase());
            } else {
                LOGGER.error("Invalid constant index {} for action {} parameter {}",
                    constantIndex, action.getName(), i);
                sb.append(" ?unknown_param?").append(i);
            }
        }
        sb.append(")");
        return sb.toString();
    }

    // Get the SAT variable for a fluent at step t
    private int getFluentVar(int fluentIndex, int timeStep) {
        Fluent fluent = this.problem.getFluents().get(fluentIndex);
        return getVar(getFluentName(fluent)+ "#" + timeStep);
    }
    // Get the SAT variable for an action at step t
    private int getActionVar(Action action, int timeStep) {
        return getVar(getActionName(action) + "#" + timeStep);
    }
    // Assign a unique integer to each variable name
    private int getVar(String name) {
        return this.varMap.computeIfAbsent(name, k -> {
            int newVar = this.varCounter++;
            this.rVarMap.put(newVar, k);
            return newVar;
        });
    }

    // Main method to convert the planning problem to SAT clauses
    public void encode() {
        LOGGER.debug("\n Encoding  - {}", new Date());

        this.clauses.clear();
        this.varMap.clear();
        this.rVarMap.clear();
        this.effectTriggerAuxVars.clear();
        this.varCounter = 1;

        // Encode various parts of the planning problem
        encodeInitialState();
        encodeGoal();
        preparingAux();               // Preprocess conditional effect triggers
        encodeActionPreconditions();  // Add preconditions
        encodeDirectEffects();        // Add direct effect
        encodeFrameAxioms();          // Handle persistence (frame axioms)
        encodeActionExclusion();      // Prevent multiple actions at same timestep

        LOGGER.debug("\n Encoding complete. Total variables: {}. Total clauses: {}",
            (this.varCounter - 1), this.clauses.size());
    }

    private void encodeInitialState() {
        LOGGER.debug("\n Encoding initial state");
        InitialState initState = this.problem.getInitialState();
        BitVector posFluents = initState.getPositiveFluents();

        for (int i = 0; i < this.problem.getFluents().size(); i++) {
            int fluentVar = getFluentVar(i, 0);
            if (posFluents.get(i)) {
                this.clauses.add(new int[]{fluentVar});
            } else {
                this.clauses.add(new int[]{-fluentVar});
            }
        }
    }

    private void encodeGoal() {
        LOGGER.debug("\n Encoding goal at horizon {}", this.horizon);
        BitVector posGoalFluents = this.problem.getGoal().getPositiveFluents();
        for (int i = 0; i < this.problem.getFluents().size(); i++) {
            if (posGoalFluents.get(i)) {
                int fluentVar = getFluentVar(i, this.horizon);
                this.clauses.add(new int[]{fluentVar});
            }
        }
        BitVector negGoalFluents = this.problem.getGoal().getNegativeFluents();
        for (int i = 0; i < this.problem.getFluents().size(); i++) {
            if (negGoalFluents.get(i)) {
                int fluentVar = getFluentVar(i, this.horizon);
                this.clauses.add(new int[]{-fluentVar});
            }
        }
    }


    // Prepare auxiliary variables for conditional effects
    private void preparingAux() {
        LOGGER.debug("\n Precomputing  And Defining the effects that will hbe Triggered for auxilary variables");


        for (int t = 0; t < this.horizon; t++) {
            //LOGGER.debug("  precompute: t = {}", t);
            int actions_t = 0; // The actions processed for this time step
            for (Action action : this.uActions) {
                actions_t++;
                String actionNames = getActionName(action);

                int actionVar = getActionVar(action, t);
                int condEffectIndex = 0; // Index of the conditional effect
                for (ConditionalEffect condEffect : action.getConditionalEffects()) {
                    String effectTriggerUniqueName = "aux" + actionNames + "_" + condEffectIndex + "#" + t;
                    int auxEffectTriggerVar = getVar(effectTriggerUniqueName);
                    this.effectTriggerAuxVars.put(effectTriggerUniqueName, auxEffectTriggerVar);
                    this.clauses.add(new int[]{-auxEffectTriggerVar, actionVar});

                    List<Integer> premiseForAuxVarDef = new ArrayList<>();
                    premiseForAuxVarDef.add(-actionVar);

                    BitVector condPos = condEffect.getCondition().getPositiveFluents();
                    for (int k_idx = 0; k_idx < this.problem.getFluents().size(); k_idx++) {
                        if (condPos.get(k_idx)) {
                            this.clauses.add(new int[]{-auxEffectTriggerVar, getFluentVar(k_idx, t)});
                            premiseForAuxVarDef.add(-getFluentVar(k_idx, t));
                        }
                    }
                    BitVector condNeg = condEffect.getCondition().getNegativeFluents();
                    for (int k_idx = 0; k_idx < this.problem.getFluents().size(); k_idx++) {
                        if (condNeg.get(k_idx)) {
                            this.clauses.add(new int[]{-auxEffectTriggerVar, -getFluentVar(k_idx, t)});
                            premiseForAuxVarDef.add(getFluentVar(k_idx, t));
                        }
                    }
                    premiseForAuxVarDef.add(auxEffectTriggerVar);
                    this.clauses.add(premiseForAuxVarDef.stream().mapToInt(Integer::intValue).toArray());
                    condEffectIndex++;
                }
            }
        }
    }
    //  Encode action preconditions
    private void encodeActionPreconditions() {
        LOGGER.debug("\n Encoding action preconditions ");
        for (int t = 0; t < this.horizon; t++) {
            for (Action action : this.uActions) {
                int actionVar = getActionVar(action, t);
                BitVector posPre = action.getPrecondition().getPositiveFluents();
                for (int i = 0; i < this.problem.getFluents().size(); i++) {
                    if (posPre.get(i)) {
                        this.clauses.add(new int[]{-actionVar, getFluentVar(i, t)});
                    }
                }
                BitVector negPre = action.getPrecondition().getNegativeFluents();
                for (int i = 0; i < this.problem.getFluents().size(); i++) {
                    if (negPre.get(i)) {
                        this.clauses.add(new int[]{-actionVar, -getFluentVar(i, t)});
                    }
                }
            }
        }
    }
    // Encode the direct effects of actions
    private void encodeDirectEffects() {
        LOGGER.debug("\n Encoding direct effects for actions ");
        for (int t = 0; t < this.horizon; t++) {
            for (Action action : this.uActions) {
                String actionName = getActionName(action);
                int condEffectIndex = 0; //  Index of the conditional effect
                for (ConditionalEffect condEffect : action.getConditionalEffects()) {
                    String effectTName = "aux" + actionName + "_" + condEffectIndex + "#" + t; // The effect Triggered name
                    if (!effectTriggerAuxVars.containsKey(effectTName)) {
                        continue;
                    }
                    int auxEffectTriggerVar = effectTriggerAuxVars.get(effectTName);

                    Effect effect = condEffect.getEffect();
                    BitVector posEff = effect.getPositiveFluents();
                    for (int i = 0; i < this.problem.getFluents().size(); i++) {
                        if (posEff.get(i)) {
                            this.clauses.add(new int[]{-auxEffectTriggerVar, getFluentVar(i, t + 1)});
                        }
                    }
                    BitVector negEff = effect.getNegativeFluents();
                    for (int i = 0; i < this.problem.getFluents().size(); i++) {
                        if (negEff.get(i)) {
                            this.clauses.add(new int[]{-auxEffectTriggerVar, -getFluentVar(i, t + 1)});
                        }
                    }
                    condEffectIndex++;
                }
            }
        }
    }
    // Encode frame axioms
    private void encodeFrameAxioms() {
        LOGGER.debug("\n Encoding Frame Axioms ");
        for (int t = 0; t < horizon; t++) {
            for (int fluentIdx = 0; fluentIdx < problem.getFluents().size(); fluentIdx++) {
                int p_t = getFluentVar(fluentIdx, t);
                int p_t_plus_1 = getFluentVar(fluentIdx, t + 1);

                List<Integer> adders = new ArrayList<>();
                List<Integer> deleters = new ArrayList<>();

                for (Action action : this.uActions) {
                    String actionKey = getActionName(action);
                    int condEffectIndex = 0;
                    for (ConditionalEffect condEffect : action.getConditionalEffects()) {
                        String effectTName = "aux" + actionKey + "_" + condEffectIndex + "#" + t;
                        if (!effectTriggerAuxVars.containsKey(effectTName)) {
                            continue;
                        }
                        int auxEffectTriggerVar = effectTriggerAuxVars.get(effectTName);

                        if (condEffect.getEffect().getPositiveFluents().get(fluentIdx)) {
                            adders.add(auxEffectTriggerVar);
                        }
                        if (condEffect.getEffect().getNegativeFluents().get(fluentIdx)) {
                            deleters.add(auxEffectTriggerVar);
                        }
                        condEffectIndex++;
                    }
                }

                List<Integer> becomesTrueClause = new ArrayList<>();
                becomesTrueClause.add(p_t);
                becomesTrueClause.add(-p_t_plus_1);
                becomesTrueClause.addAll(adders);
                clauses.add(becomesTrueClause.stream().mapToInt(Integer::intValue).toArray());

                List<Integer> becomesFalseClause = new ArrayList<>();
                becomesFalseClause.add(-p_t);
                becomesFalseClause.add(p_t_plus_1);
                becomesFalseClause.addAll(deleters);
                clauses.add(becomesFalseClause.stream().mapToInt(Integer::intValue).toArray());
            }
        }
    }

    private void encodeActionExclusion() {
        LOGGER.debug(" \n Encoding action exclusion ");
        for (int t = 0; t < this.horizon; t++) {
            if (this.uActions.size() > 1) {
                List<Integer> actionVarsAtT = new ArrayList<>();
                for(Action action : this.uActions) {
                    actionVarsAtT.add(getActionVar(action, t));
                }

                List<Integer> uniqueActionVarsAtTForExclusion = actionVarsAtT.stream().distinct().collect(Collectors.toList());

                if (uniqueActionVarsAtTForExclusion.size() > 1) {
                    for (int i = 0; i < uniqueActionVarsAtTForExclusion.size(); i++) {
                        for (int j = i + 1; j < uniqueActionVarsAtTForExclusion.size(); j++) {
                            this.clauses.add(new int[]{-uniqueActionVarsAtTForExclusion.get(i), -uniqueActionVarsAtTForExclusion.get(j)});
                        }
                    }
                }
            }
        }
    }

    // Decode the SAT model into a plan
    public List<String> decodeModel(int[] model) {
        LOGGER.debug("\n Decoding");
        List<String> planActionNames = new ArrayList<>();
        if (model == null) return planActionNames;

        Map<Integer, List<String>> actionsByTimeStep = new TreeMap<>();

        for (int varId : model) {
            if (varId > 0 && rVarMap.containsKey(varId)) {
                String varName = rVarMap.get(varId);
                int symbIdx = varName.lastIndexOf('#');

                if (symbIdx != -1) {
                    String namePart = varName.substring(0, symbIdx);

                    if (namePart.startsWith("(") && namePart.endsWith(")") && !namePart.startsWith("aux")) {
                        boolean isAnActionName = false;
                        for(Action problemAct : this.uActions){
                            if(getActionName(problemAct).equals(namePart)){
                                isAnActionName = true;
                                break;
                            }
                        }
                        if (isAnActionName) {
                            try {
                                String timeSuffix = varName.substring(symbIdx + 1);
                                int timeStep = Integer.parseInt(timeSuffix);
                                actionsByTimeStep.computeIfAbsent(timeStep, k -> new ArrayList<>()).add(namePart);
                            } catch (NumberFormatException e) {
                                LOGGER.warn("Could not parse time step from decoded action variable name: {}", varName);
                            }
                        }
                    }
                }
            }
        }

        actionsByTimeStep.values().forEach(planActionNames::addAll);

        if (LOGGER.isDebugEnabled() && !planActionNames.isEmpty()) {
            StringBuilder sb = new StringBuilder("Decoded plan steps\n");
            actionsByTimeStep.forEach((timeStep, actionList) -> {
                for (String actionName : actionList) {
                    sb.append(String.format("  t=%d: %s%n", timeStep, actionName));
                }
            });
            LOGGER.debug(sb.toString());
        }
        return planActionNames;
    }

    // Get the SAT clauses
    public List<int[]> getClauses() {
        return Collections.unmodifiableList(clauses);
    }

    // Get the number of variables used
    public int getVarCount() {
        return this.varCounter - 1;
    }

}
