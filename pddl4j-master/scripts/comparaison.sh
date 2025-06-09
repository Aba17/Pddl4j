#!/bin/bash


# This  script shoul be run in the project root (pddl4j-master)
if [ ! -f "build.gradle" ]; then
    echo "ERROR: This script must be run from the project's root directory."
    echo "Please 'cd' to the 'pddl4j-master' directory and run it from there:"
    echo "Example: ./scripts/comparaison.sh"
    exit 1
fi

# --- Configuration ---
DOMAINS=("blocks" "depots" "gripper")
RESULTS_DIR="./results"
BENCH_PATH="./src/main/java/fr/uga/pddl4j/examples/sat/benchmarks"

# The Fat Jar
FAT_JAR="./build/libs/pddl4j-4.0.0-all.jar"

# --- Planner Commands ---
SAT_PLANNER_CLASS="fr.uga.pddl4j.examples.sat.classes.Planner"
HSP_WRAPPER_CLASS="fr.uga.pddl4j.examples.sat.hsp.ASP"

MY_PLANNER="java -cp $FAT_JAR $SAT_PLANNER_CLASS -h -1 -M 30"
HSP_PLANNER="java -cp $FAT_JAR $HSP_WRAPPER_CLASS"

# --- Main Script ---
mkdir -p "$RESULTS_DIR"
mkdir -p "$RESULTS_DIR/logs"
echo "domain,problem,planner,runtime,makespan" > "$RESULTS_DIR/performance.csv"

echo "Starting benchmarks"

for domain in "${DOMAINS[@]}"; do
    DOMAIN_FILE="$BENCH_PATH/$domain/domain.pddl"
    if [ ! -f "$DOMAIN_FILE" ]; then continue; fi

    find "$BENCH_PATH/$domain" -type f -name 'p*.pddl' | sort -V | while read -r PROBLEM_FILE; do
        problem=$(basename "$PROBLEM_FILE")
        LOG_HSP="$RESULTS_DIR/logs/HSP_${domain}_${problem}.log"
        LOG_SAT="$RESULTS_DIR/logs/SAT_${domain}_${problem}.log"

        echo "---"
                echo "Running HSP on $domain/$problem"
                START_MS=$(date +%s%3N)
                $HSP_PLANNER "$DOMAIN_FILE" "$PROBLEM_FILE" > "$LOG_HSP" 2>&1
                END_MS=$(date +%s%3N)
                RUNTIME_HSP=$((END_MS - START_MS))
                LENGTH_HSP=$(grep -c "(" "$LOG_HSP")
                echo "$domain,$problem,HSP,$RUNTIME_HSP,$LENGTH_HSP" >> "$RESULTS_DIR/performance.csv"

                echo "Running SAT on $domain/$problem"
                START_MS=$(date +%s%3N)
                $MY_PLANNER "$DOMAIN_FILE" "$PROBLEM_FILE" > "$LOG_SAT" 2>&1
                END_MS=$(date +%s%3N)
                RUNTIME_SAT=$((END_MS - START_MS))
                LENGTH_SAT=$(grep -c "(" "$LOG_SAT")
                echo "$domain,$problem,SAT,$RUNTIME_SAT,$LENGTH_SAT" >> "$RESULTS_DIR/performance.csv"
    done
done

echo "Benchmarking complete. Results saved to $RESULTS_DIR/performance.csv"
echo "The logs are in $RESULTS_DIR/logs/"
