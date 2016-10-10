package edu.ucsc.cs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import edu.umd.cs.psl.database.Database;
import edu.umd.cs.psl.evaluation.statistics.filter.AtomFilter;
import edu.umd.cs.psl.evaluation.statistics.filter.MaxValueFilter;
import edu.umd.cs.psl.model.argument.GroundTerm;
import edu.umd.cs.psl.model.atom.GroundAtom;
import edu.umd.cs.psl.model.atom.ObservedAtom;
import edu.umd.cs.psl.model.predicate.Predicate;
import edu.umd.cs.psl.util.database.Queries;

public class OutputWriter {
	
	private final Database result;
	private Predicate p;
   	private String name;
    private String fold;
    private String subDir;
	
	public OutputWriter(Database result, Predicate p, String name, String fold, String subDir) {
		this.result = result;
		this.p = p;
        this.name = name;
        this.fold = fold;
        this.subDir = subDir;
	}
	
	public void outputToFile(boolean appendOption){
		BufferedWriter writer = null;
		String dir = "output" + java.io.File.separator + subDir;
		String resultsFile = dir  + java.io.File.separator + name + "-" + fold + ".csv";
                
		try {
			File file = new File(dir);
			if (!file.exists()) {
				if (file.mkdir()) {
					System.out.println("Directory is created!");
				} else {
					System.out.println("Failed to create directory!");
				}
			}
			writer = new BufferedWriter(new FileWriter(resultsFile, appendOption));
			
			for (GroundAtom atom : Queries.getAllAtoms(result, p)){
				GroundTerm[] terms = atom.getArguments();
                                
                                StringBuilder output = new StringBuilder();
                                for (GroundTerm t : terms){
                                    output.append(t + ",");
                                }
                                
				writer.append(output.toString() + atom.getValue() + "\n");
                                writer.flush();
			}
        } catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
}
