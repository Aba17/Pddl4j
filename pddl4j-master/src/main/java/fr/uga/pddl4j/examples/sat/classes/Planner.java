package fr.uga.pddl4j.examples.sat.classes;


import fr.uga.pddl4j.parser.DefaultParsedProblem;
import fr.uga.pddl4j.plan.Plan;
import fr.uga.pddl4j.plan.SequentialPlan;
import fr.uga.pddl4j.planners.AbstractPlanner;
import fr.uga.pddl4j.problem.DefaultProblem;
import fr.uga.pddl4j.problem.InitialState;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.State;
import fr.uga.pddl4j.problem.operator.Action;

import fr.uga.pddl4j.problem.operator.Condition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.TimeoutException;

import picocli.CommandLine;

import java.util.*;

@CommandLine.Command(name = "SATPlanner",
        version = "Planner 1.0",
        description = "Solves a planning problem using a SAT-based approach.",
        sortOptions = false,
        mixinStandardHelpOptions = true)
public class Planner extends AbstractPlanner {

    private static final Logger LOGGER = LogManager.getLogger(Planner.class.getName());

    @CommandLine.Option(names = {"-h", "--horizon"}, defaultValue = "-1",
        paramLabel = "<horizon>", description = "Set the number total of steps")
    private Integer fixedHorizon = null;
    @CommandLine.Option(names = {"-M", "--maxsteps"}, defaultValue = "30",
        paramLabel = "<maxsteps>", description = "Set the maximum of steps in case of the number of steps is unknow")
    private int maxHorizon = 30;

    private int timeoutSec = 300;




    @Override
    public Problem instantiate(DefaultParsedProblem parsedProblem) {
        //We create a problem
        final Problem problem = new DefaultProblem(parsedProblem);
        // We instantiate the planning problem
        problem.instantiate();
        return problem;
    }

    @Override
    public Plan solve(Problem problem) {
        //We create an encoder
        Encoder encoder;
        //To check weather the goal is satisfied
        boolean satisfied = isGoalSatInit(problem); // Use the corrected helper
        if (fixedHorizon != null && fixedHorizon>=0) {
            //LOGGER.debug("Solving with the horizon: " + fixedHorizon);
            encoder = new Encoder(problem, fixedHorizon);
            return solveWithHorizon(problem, fixedHorizon, encoder, satisfied);
        } else {
            //LOGGER.debug("Fo a max of  " + maxHorizon + " horizons");
            for (int h = 1; h <= maxHorizon; h++) {
                encoder = new Encoder(problem, h);
                Plan plan = solveWithHorizon(problem, h, encoder, satisfied);
                if (plan != null) {
                    return plan;
                }
            }
              LOGGER.warn("No plan found within maximum horizon " + maxHorizon);
            return null;
        }
    }

    private Plan solveWithHorizon(Problem problem, int horizon, Encoder encoder, boolean satisfied) {
        try {

            if (satisfied) {
                LOGGER.debug("Goal is already satisfied in the initial state. Nothing to do. Empty plan returned for H={}.", horizon);
                return new SequentialPlan();
            }
            if (horizon == 0 && !satisfied) { // Goal not met, H=0
                return null;
            }
            // Now we can encode
            encoder.encode();
            //List of clauses encoded
            List<int[]> clauses = encoder.getClauses();


            if (clauses.isEmpty() && encoder.getVarCount() == 0 && horizon > 0) {
                  LOGGER.warn("No clauses and the goal is different to thye i nitial state: UNSAT.");
                return null;
            }


            ISolver solver = SolverFactory.newDefault();
            solver.setTimeout(this.timeoutSec);

            if (encoder.getVarCount() > 0) {
                //The number of variables
                solver.newVar(encoder.getVarCount());
            } else {
                return null;
            }

            solver.setExpectedNumberOfClauses(clauses.size());
            LOGGER.debug("H={}: Adding {} clauses for {} variables", horizon, clauses.size(), encoder.getVarCount());
            for (int[] clause : clauses) {
                if (clause == null || clause.length == 0) continue;
                try {
                    solver.addClause(new VecInt(clause));
                } catch (ContradictionException e) {
                    return null;
                }
            }

            LOGGER.debug("Solving SAT instansce for horizon H={}", horizon);

            if (solver.isSatisfiable()) {
                  LOGGER.debug("Model found for {} steps", horizon);
                int[] model = solver.model();
                List<String> actionNames = encoder.decodeModel(model);
                SequentialPlan plan = new SequentialPlan();
                  LOGGER.debug("Decoding the actions that aere found");

                int t_idx = 0;
                for (String name : actionNames) {
                    Action matchedAction = null;
                    for (Action problemAction : problem.getActions()) {
                        if (encoder.getActionName(problemAction).equals(name)) {
                            matchedAction = problemAction;
                            break;
                        }
                    }
                    if (matchedAction != null) {
                        boolean added = plan.add(t_idx, matchedAction);
                        if (added) {
                            //  LOGGER.warn("- (plan step {}) {}", t_idx, name);
                        } else {
                            //  LOGGER.warn("Failed to add action {} ({})  plan for step {}.", matchedAction, name, t_idx);
                        }
                        t_idx++;
                    } else {
                        //  LOGGER.warn("Can ,ot find action for the name '{}'", name);
                    }
                }
                System.out.println("plan length: " + plan.size()); // This is the key line!

                 LOGGER.debug("Plan well reconstructed with {} actions for {} steps",plan.size(),horizon);
                return plan;

            } else {
                  LOGGER.debug("Problem is UNSAT.");
                return null;
            }

        } catch (TimeoutException e) {
              LOGGER.warn("Time out");
            return null;
        } catch (IllegalArgumentException e) {
              LOGGER.error("Error in  solving: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
              LOGGER.error("Error  {}", e.getMessage(), e);
            return null;
        }
    }
    private boolean isConditionEmpty(Condition condition) {
        if (condition == null) {
            return true;
        }
        boolean hasPositiveFluents = condition.getPositiveFluents() != null && !condition.getPositiveFluents().isEmpty();
        boolean hasNegativeFluents = condition.getNegativeFluents() != null && !condition.getNegativeFluents().isEmpty();
        boolean hasNumericConstraints = condition.getNumericConstraints() != null && !condition.getNumericConstraints().isEmpty();

        return !hasPositiveFluents && !hasNegativeFluents && !hasNumericConstraints;
    }

    private boolean isGoalSatInit(Problem problem) {
        //To check if the goasl is satisfied in the initial state
        InitialState problemInitialState = problem.getInitialState();
        Condition goalCondition = problem.getGoal();

        if (problemInitialState == null || isConditionEmpty(goalCondition)) {
            return false;
        }
        //Important : we need a state to check if it satisfies the condition
        return new State(problemInitialState).satisfy(goalCondition);
    }

    @Override
    public boolean isSupported(Problem problem) {
        return problem != null;
    }
    public static void main(String[] args) {
        try {
            Planner planner = new Planner();
            CommandLine cmd = new CommandLine(planner);
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        } catch (Throwable t) {
            System.err.println("FATAL ERROR: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }


}
