import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import os
import numpy as np

# Define paths
RESULTS_DIR = "./results/"
CSV_FILE = os.path.join(RESULTS_DIR, "performance.csv")
PDF_OUTPUT_FILE = os.path.join(RESULTS_DIR, "comparison_plots.pdf")

# --- Check for input file ---
if not os.path.exists(CSV_FILE):
    print(f"Error: Results file not found at '{CSV_FILE}'")
    print("Please run the 'comparaison.sh' script first.")
    exit(1)

# --- Read the performance file
df = pd.read_csv(CSV_FILE)

# Get the unique domains from the CSV
domains = df['domain'].unique()
print(f"Found domains: {', '.join(domains)}")

# --- Generate Plots and Save to PDF ---
with PdfPages(PDF_OUTPUT_FILE) as pdf:
    for domain in domains:
        print(f"Processing domain: {domain}...")
        domain_df = df[df['domain'] == domain].copy()

        # Filter out problems where both planners failed (runtime 0 or makespan 0)
        # This cleans up the plots.
        domain_df = domain_df[domain_df['runtime'] > 0]

        if domain_df.empty:
            print(f"  Skipping domain '{domain}' due to no successful runs.")
            continue

        # --- Sort problems based on HSP runtime ---
        hsp_df = domain_df[domain_df['planner'] == 'HSP'].sort_values('runtime')
        problem_order = hsp_df['problem'].tolist()

        if not problem_order:
            print(f"  Skipping domain '{domain}' as no HSP results were found for sorting.")
            continue

        domain_df['problem'] = pd.Categorical(domain_df['problem'], categories=problem_order, ordered=True)
        domain_df = domain_df.sort_values('problem')

        # Pivot the table for the bar plot
        df_pivot_runtime = domain_df.pivot(index='problem', columns='planner', values='runtime')
        df_pivot_makespan = domain_df.pivot(index='problem', columns='planner', values='makespan')

        # --- Plot 1: Runtime Comparison (Bar Chart) ---
        fig, ax = plt.subplots(figsize=(12, 6))
        df_pivot_runtime.plot(kind='bar', ax=ax, width=0.8)
        ax.set_title(f'Runtime Comparison in "{domain.capitalize()}" Domain', fontsize=16)
        ax.set_ylabel('Runtime (ms)')
        ax.set_xlabel('Problems (Ordered by HSP Runtime)')
        ax.tick_params(axis='x', rotation=45)
        ax.grid(axis='y', linestyle='--', alpha=0.7)
        ax.legend(title='Planner')
        plt.tight_layout()
        pdf.savefig(fig) # Save the current figure to the PDF
        plt.close(fig)

        # --- Plot 2: Makespan Comparison (Bar Chart) ---
        fig, ax = plt.subplots(figsize=(12, 6))
        df_pivot_makespan.plot(kind='bar', ax=ax, width=0.8)
        ax.set_title(f'Makespan (Plan Length) Comparison in "{domain.capitalize()}" Domain', fontsize=16)
        ax.set_ylabel('Makespan (Number of Actions)')
        ax.set_xlabel('Problems (Ordered by HSP Runtime)')
        ax.tick_params(axis='x', rotation=45)
        ax.grid(axis='y', linestyle='--', alpha=0.7)
        ax.legend(title='Planner')
        plt.tight_layout()
        pdf.savefig(fig) # Save the current figure to the PDF
        plt.close(fig)

print(f"\n All 8 figures have been successfully generated and saved to '{PDF_OUTPUT_FILE}'")
