package edu.ucsc.cs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.psl.database.DataStore;
import edu.umd.cs.psl.database.Database;
import edu.umd.cs.psl.database.Partition;
import edu.umd.cs.psl.database.loading.Inserter;
import edu.umd.cs.psl.model.argument.GroundTerm;
import edu.umd.cs.psl.model.argument.Term;
import edu.umd.cs.psl.model.atom.GroundAtom;
import edu.umd.cs.psl.model.atom.QueryAtom;
import edu.umd.cs.psl.model.atom.RandomVariableAtom;
import edu.umd.cs.psl.model.predicate.Predicate;
import edu.umd.cs.psl.model.predicate.StandardPredicate;
import edu.umd.cs.psl.util.collection.HashList;
import edu.umd.cs.psl.util.database.Queries;

public class TriadBlocking{

	private static final Logger log = LoggerFactory.getLogger(TriadBlocking.class);

	// Blocking Methods: Begin
	// =======================	

	// == Naive KNN Blocking for one atom
	// SF: This method could definitely be optimized!
	// positiveValues: Interested in the atoms with the highest value or lowest
	// kActvive: Whe k in k-nn!
	// termIndex: Which term in the predicate. e.g., P(1,2,3) and -1 for any location
	// activeTerm: The Term to look for
	// allSimilarities: Set of all similairites.
	public Set<GroundAtom> knnBlockingOne(boolean positiveValues, int kActive, int termIndex, GroundTerm activeTerm, Set<GroundAtom> allSimilarities){

		// Filtering the similiarity for only one term
		Set<GroundAtom> querySimilarities = GetQueryAtoms(allSimilarities, termIndex, activeTerm);

		// Sorting them
		List<GroundAtom> resultAtoms = new ArrayList<GroundAtom>(querySimilarities);

		Collections.sort(resultAtoms, new AtomComparator());
		if (positiveValues)
			Collections.reverse(resultAtoms);

		if (resultAtoms.size() <= kActive){
			kActive = resultAtoms.size();
		}

		return new HashSet<GroundAtom>(resultAtoms.subList(0,kActive));		
	}

	
	// == Naive KNN Blocking Set
	// Same arguments as above
	public Set<GroundAtom> knnBlockingSet(boolean positiveValues, int kActive, int termIndex, Set<GroundTerm> activeTermSet, Set<GroundAtom> allSimilarities){

		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Iterator<GroundTerm> itrTerm = activeTermSet.iterator();
		
		while (itrTerm.hasNext())
		{
			GroundTerm item = itrTerm.next();
			resultAtoms.addAll(knnBlockingOne(positiveValues, kActive, termIndex, item, allSimilarities));
		}

		return resultAtoms;
	}

	
	// == Blocking based on the observed links in the training set
	public Set<GroundAtom> observedLinksInTraining(Set<GroundAtom> allSimilarities, Set<GroundAtom> trainingAtoms, int kLessActive, int kMoreActive){

		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Set<GroundTerm> moreActiveSet = new HashSet<GroundTerm>();
		Set<GroundTerm> lessActiveSet = new HashSet<GroundTerm>();

		for(GroundAtom ga: trainingAtoms){
			double value = ga.getValue();
			GroundTerm[] terms = ga.getArguments();
			int arity = ga.getArity();

			if(value == 1.0){
				for(int i = 0; i < arity; i++){
					moreActiveSet.add(terms[i]);
				}
			}else{
				for(int i = 0; i < arity; i++){
					lessActiveSet.add(terms[i]);
				}
			}
		}

		resultAtoms = knnBlockingSet(true, kMoreActive, 0,  moreActiveSet, allSimilarities);
		resultAtoms.addAll( knnBlockingSet(true, kLessActive, 0, lessActiveSet, allSimilarities) );
		return resultAtoms;		
	}

	//blocking based on links in test set
	public Set<GroundAtom> allLinksInTesting(Set<GroundAtom> allSimilarities, Set<GroundAtom> testingAtoms, int kLessActive, int kMoreActive){

		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Set<GroundTerm> moreActiveSet = GetQueryTerms(testingAtoms, 0);
		Set<GroundTerm> lessActiveSet = GetQueryTerms(allSimilarities, 0);
		lessActiveSet.removeAll(moreActiveSet);

		// System.out.println( "more active set: " + moreActiveSet.size() + ", less active set: " + lessActiveSet.size() );

		resultAtoms = knnBlockingSet(true, kMoreActive,0,  moreActiveSet, allSimilarities);
		resultAtoms.addAll( knnBlockingSet(true, kLessActive, 0, lessActiveSet, allSimilarities) );
		return resultAtoms;		
	}

	//blocking based on similarities of test set drugs linked to observed training links
	public Set<GroundAtom> testTermsWithObservedLinks(Set<GroundAtom> allSimilarities, Set<GroundAtom> trainingAtoms, Set<GroundAtom> testingAtoms, int kLessActive, int kMoreActive){

		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Set<GroundTerm> testTerms = GetQueryTerms(testingAtoms, 0);
		Set<GroundAtom> activeSimilarities = new HashSet<GroundAtom>();
		Map<List<GroundTerm> ,GroundAtom> similaritiesMap = this.getGroundAtomsMap(allSimilarities);

		for(GroundAtom ga: trainingAtoms){
			double value = ga.getValue();
			GroundTerm[] terms = ga.getArguments();
			int arity = ga.getArity();

			if(value == 1.0){
				if (testTerms.contains( terms[arity-1] )){
					if (similaritiesMap.containsKey( Arrays.asList(terms) ) ){
						activeSimilarities.add( similaritiesMap.get(Arrays.asList(terms)) );	
					}					
				}
			}
		}
		resultAtoms = knnBlockingSet(true, kMoreActive, 0, GetQueryTerms(activeSimilarities, 0), activeSimilarities);

		activeSimilarities.removeAll( resultAtoms );
		resultAtoms.addAll( knnBlockingSet( true, kLessActive, 0, GetQueryTerms(activeSimilarities, 1), allSimilarities));

		return resultAtoms;		
	}

	//blocking based on observed links in training that have a link to a test set drug
	public Set<GroundAtom> observedTrainWithTestTerm(Set<GroundAtom> allSimilarities, Set<GroundAtom> trainingAtoms, Set<GroundAtom> testingAtoms, int kLessActive, int kMoreActive){

		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Set<GroundTerm> testTerms = GetQueryTerms(testingAtoms, 0);

		Set<GroundAtom> activeSet = new HashSet<GroundAtom>();

		for(GroundAtom ga: trainingAtoms){
			double value = ga.getValue();
			GroundTerm[] terms = ga.getArguments();
			int arity = ga.getArity();

			if(value == 1.0){
				if (testTerms.contains( terms[arity-1] )){
					activeSet.add(ga);
				}
			}
		}
		resultAtoms = knnBlockingSet(true, kMoreActive, 0, GetQueryTerms(activeSet, 0), allSimilarities);
		activeSet.removeAll(resultAtoms);
		resultAtoms.addAll( knnBlockingSet( true, kLessActive, 0, GetQueryTerms(activeSet, 1), allSimilarities));

		return resultAtoms;	
	}



	
	// Blocking Methods: End
	// =====================


	// Other methods needed for the blocking methods:
	// ==============================================

	// == Extracts the terms (Drugs) out of QueryAtoms set (-1 for all)
	public Set<GroundTerm> GetQueryTerms(Set<GroundAtom> queryAtoms, int index){
		
		Set<GroundTerm> terms = new HashSet<GroundTerm>();
		Iterator<GroundAtom> itr = queryAtoms.iterator();
		
		int arity = 0;
		GroundAtom item = null;
		
		while (itr.hasNext())
		{
			item = itr.next();
			arity = item.getArity();
			
			if (index==-1) //to get all terms
			{
				for (int i=0; i<arity; i++)
				{
					terms.add(item.getArguments()[i]);
				}
			}else{
				terms.add(item.getArguments()[index]);
			}
				
		
		}
		return terms;
	
	}

	public Map<List<GroundTerm>, GroundAtom> getGroundAtomsMap(Set<GroundAtom> atoms){
		Map<List<GroundTerm>, GroundAtom> resultMap = new HashMap<List<GroundTerm>,GroundAtom>();
		Iterator<GroundAtom> itrAtom = atoms.iterator();
		while( itrAtom.hasNext()){
			GroundAtom atom = itrAtom.next();
			List<GroundTerm> terms = new ArrayList<GroundTerm>(Arrays.asList(atom.getArguments()));
			resultMap.put( terms, atom );
		}

		return resultMap;
	}



	// == Get all the atoms with specific Term in them
	private Set<GroundAtom> GetQueryAtoms(Set<GroundAtom> allAtoms, int termIndex, GroundTerm activeTerm) {
		
		Set<GroundAtom> resultAtoms = new HashSet<GroundAtom>();
		Iterator<GroundAtom> itrAtom = allAtoms.iterator();
		
		int arity=0;
		boolean selected = false;
		
		while (itrAtom.hasNext())
		{
			GroundAtom item = itrAtom.next();
			selected = false;
		
			if (arity == 0)
				arity = item.getArity();

			if (termIndex==-1) //to get all terms
			{
				for (int i=0; i<arity; i++)
				{
					if ( activeTerm.equals(item.getArguments()[i]) )
						selected = true;
				}
			}else{
				if ( activeTerm.equals(item.getArguments()[termIndex]) )
						selected = true;
			}

			if (selected)
				resultAtoms.add(item);
		}

	return resultAtoms;

	}




	// == Used to sort the predicates
	private class AtomComparator implements Comparator<GroundAtom> {
		public int compare(GroundAtom a1, GroundAtom a2) {
			if (a1.getValue() > a2.getValue())
				return 1;
			else if (a1.getValue() == a2.getValue())
				return a1.toString().compareTo(a2.toString());
			else
				return -1;
		}
	}

// End of class
}
