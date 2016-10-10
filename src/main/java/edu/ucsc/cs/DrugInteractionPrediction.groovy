package edu.ucsc.cs;

import java.util.Collections;
import java.util.Iterator;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import edu.umd.cs.psl.application.inference.MPEInference
import edu.umd.cs.psl.application.learning.weight.maxlikelihood.MaxLikelihoodMPE
import edu.umd.cs.psl.application.learning.weight.em.PairedDualLearner
import edu.umd.cs.psl.application.learning.weight.maxmargin.L1MaxMargin
import edu.umd.cs.psl.config.*
import edu.umd.cs.psl.core.*
import edu.umd.cs.psl.core.inference.*
import edu.umd.cs.psl.database.DataStore
import edu.umd.cs.psl.database.Database
import edu.umd.cs.psl.database.DatabasePopulator
import edu.umd.cs.psl.database.Partition
import edu.umd.cs.psl.database.rdbms.RDBMSDataStore
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver
import edu.umd.cs.psl.database.rdbms.driver.H2DatabaseDriver.Type
import edu.umd.cs.psl.evaluation.result.*
import edu.umd.cs.psl.evaluation.statistics.RankingScore
import edu.umd.cs.psl.evaluation.statistics.SimpleRankingComparator
import edu.umd.cs.psl.groovy.*
import edu.umd.cs.psl.model.argument.ArgumentType
import edu.umd.cs.psl.model.argument.GroundTerm
import edu.umd.cs.psl.model.argument.Variable
import edu.umd.cs.psl.model.atom.GroundAtom;
import edu.umd.cs.psl.model.atom.QueryAtom
import edu.umd.cs.psl.model.kernel.rule.AbstractRuleKernel
import edu.umd.cs.psl.model.parameters.PositiveWeight
import edu.umd.cs.psl.model.predicate.Predicate
import edu.umd.cs.psl.ui.loading.*
import edu.umd.cs.psl.util.database.*

import com.google.common.collect.Iterables
import edu.umd.cs.psl.model.kernel.CompatibilityKernel
import edu.umd.cs.psl.model.parameters.PositiveWeight
import edu.umd.cs.psl.model.parameters.Weight

class DrugInteractionPrediction {
	Logger log = LoggerFactory.getLogger(this.class);

	class DrugInteractionFold {
		public int cvFold;
		public int wlFold;

		public Partition cvTruth;
		public Partition cvTest;
		public Partition cvTrain;

		public Partition wlTruth;
		public Partition wlTest;
		public Partition wlTrain;

		public Partition wlSim;
		public Partition cvSim;

		DrugInteractionFold(Integer cvFold, Integer wlFold){
			this.cvFold = cvFold;
			this.wlFold = wlFold;
		}

	}

	class DrugInteractionExperiment {
		public ConfigManager cm;
		public ConfigBundle cb;
		public double initialWeight;
		public int numFolds;
		public int numDrugs;
		public String interaction_type;
		public String experiment_name;
		public boolean doWeightLearning;
		public boolean createNewDataStore;
		public String base_dir;
		public String interactions_dir;
		public String similarities_dir;
		public int blockingType;
		public int blockingNum;

		Set<Predicate> closedPredicatesInference;
		Set<Predicate> closedPredicatesTruth;

	}

	class DrugInteractionEvalResults {
    // Creating the variables to save the results of each fold
	    public double[] AUC_Folds;
	    public double[] AUPR_P_Folds;
	    public double[] AUPR_N_Folds;

	    DrugInteractionEvalResults(Integer folds){
	    	this.AUC_Folds = new double[folds+1];
	    	this.AUPR_P_Folds = new double[folds+1];
	    	this.AUPR_N_Folds = new double[folds+1];
    	}
    } 


    def setupConfig(numFolds, numDrugs, experimentName, blocking_k, blocking_type, interaction_type, createDS){
    	DrugInteractionExperiment config = new DrugInteractionExperiment();
    	config.cm = ConfigManager.getManager();
    	config.cb = config.cm.getBundle("fakhraei_sridhar_bioinformatics");

    	config.initialWeight = 5.0;
    	config.numFolds = numFolds;
    	config.numDrugs = numDrugs;
    	config.doWeightLearning = true;
    	config.createNewDataStore = createDS;

    	config.blockingNum = blocking_k;
    	config.interaction_type = interaction_type;
    	config.blockingType = blocking_type;
    	config.experiment_name = experimentName;


    	config.base_dir = 'data'+java.io.File.separator;
    	config.interactions_dir = config.base_dir + config.experiment_name + java.io.File.separator + config.interaction_type + java.io.File.separator;
    	config.similarities_dir = config.blockingType + '_' + config.blockingNum.toString() + java.io.File.separator;

    	return config

    }

    def setupDataStore(config) {
    	String dbpath = "/scratch1/psl_fsbio_collective";
    	DataStore data = new RDBMSDataStore(new H2DatabaseDriver(Type.Disk, dbpath, config.createNewDataStore), config.cb);

    	return data
    }

    def defineModel(config, data, m){

    	m.add predicate : "ATCSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "SideEffectSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "GOSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "ligandSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "chemicalSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "seqSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "distSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "interacts" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate: "validInteraction" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]

    	//m.add predicate : "ignoredInteracts" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]

    	Random rand = new Random();

    	m.add rule : (ATCSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (SideEffectSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (GOSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (ligandSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (chemicalSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (seqSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (distSimilarity(D1, D2) & interacts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight

		//prior
		m.add rule : validInteraction(D1,D2) >> ~interacts(D1,D2),  weight : config.initialWeight

		m.add PredicateConstraint.Symmetric, on : interacts

		Map<CompatibilityKernel,Weight> weights = new HashMap<CompatibilityKernel, Weight>()
		
		for (CompatibilityKernel k : Iterables.filter(m.getKernels(), CompatibilityKernel.class))
		weights.put(k, k.getWeight());

		config.closedPredicatesInference = [validInteraction, ATCSimilarity, distSimilarity, seqSimilarity, ligandSimilarity, GOSimilarity, SideEffectSimilarity, chemicalSimilarity];
		config.closedPredicatesTruth = [interacts];

		return weights

	}

	def defineNonCollectiveModel(config, data, m){

		m.add predicate : "ATCSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "SideEffectSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "GOSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "ligandSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "chemicalSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "seqSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "distSimilarity" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate : "interacts" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate: "validInteraction" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]
    	m.add predicate: "observedInteracts" , types:[ArgumentType.UniqueID, ArgumentType.UniqueID]

    	// check if validInteraction(D1, D3) still needed
    	m.add rule : (ATCSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (SideEffectSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (GOSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (ligandSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (chemicalSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (seqSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight
		m.add rule : (distSimilarity(D1, D2) & observedInteracts(D1, D3) & validInteraction(D1, D3) & validInteraction(D2, D3) & (D2 - D3) & (D1 - D2))>>interacts(D2, D3) , weight:config.initialWeight

		//prior
		m.add rule : validInteraction(D1,D2) >> ~interacts(D1,D2),  weight : config.initialWeight

		m.add PredicateConstraint.Symmetric, on : interacts

		Map<CompatibilityKernel,Weight> weights = new HashMap<CompatibilityKernel, Weight>()
		
		for (CompatibilityKernel k : Iterables.filter(m.getKernels(), CompatibilityKernel.class))
		weights.put(k, k.getWeight());

		config.closedPredicatesInference = [observedInteracts, validInteraction, ATCSimilarity, distSimilarity, seqSimilarity, ligandSimilarity, GOSimilarity, SideEffectSimilarity, chemicalSimilarity];
		config.closedPredicatesTruth = [interacts];

		return weights
	}


	def loadData(data,config) { 

	    // Loading the data
	    // ================

	    // Creating the partition to read the data
	    Partition readSimilarities =  data.getPartition("all_sims");

	    def insert;
	    def simDir = config.base_dir + config.experiment_name + java.io.File.separator;
	    
	    for (Predicate p : [ATCSimilarity, distSimilarity, seqSimilarity, ligandSimilarity, GOSimilarity, SideEffectSimilarity, chemicalSimilarity])
	    {
	    	log.debug("Reading " + p.getName());
	    	insert = data.getInserter(p, readSimilarities)
	    	InserterUtils.loadDelimitedDataTruth(insert, simDir +p.getName().toLowerCase()+".csv")
	    }
	}



	def loadSimilarityData(config, data, df){
		def cvfold = df.cvFold;
		def wlfold = df.wlFold

		log.debug("\n-------------------");
		def timeNow = new Date();
		log.debug("Fold "+ cvfold +" Start: "+timeNow);

		def current_sim_dir = config.interactions_dir + cvfold + java.io.File.separator + config.similarities_dir;

		if (!config.createNewDataStore){
			//clearing the partitions in case already in datastore
			data.deletePartition(data.getPartition(df.wlSim));
			data.deletePartition(data.getPartition(df.cvSim));
		}

		df.wlSim = data.getPartition("wlsim" + wlfold)
		df.cvSim = data.getPartition("cvsim" + cvfold)

		// Reading triad target similarities from file
		for (Predicate p : [ATCSimilarity, distSimilarity, seqSimilarity, ligandSimilarity, GOSimilarity, SideEffectSimilarity, chemicalSimilarity])
		{
			log.debug("Reading " + p.getName());
			def insert = data.getInserter(p, df.cvSim)
			InserterUtils.loadDelimitedDataTruth(insert, current_sim_dir+p.getName().toLowerCase()+"_cv.csv")
			insert = data.getInserter(p, df.wlSim)
			InserterUtils.loadDelimitedDataTruth(insert, current_sim_dir+p.getName().toLowerCase()+"_wl.csv")

		}

	}

	def resetDataForFold(config, data, df){
		def cvFold = df.cvFold;
		def wlFold = df.wlFold;

		df.wlSim = data.getPartition("wlsim" + wlFold);
		df.cvSim = data.getPartition("cvsim" + cvFold);

		df.cvTruth =  data.getPartition("cvlabels" + cvFold); // Labels for the Cross-validation hold-outs
		df.cvTest =  data.getPartition("cvwrite" + cvFold); // Partition to write the predictions in
		df.cvTrain =  data.getPartition("cvread" + cvFold); // Observed training data for the training (i.e., all data minus hold-outs)

		df.wlTruth =  data.getPartition("wllabels" + wlFold); // Labels for the weight Learning
		df.wlTrain =  data.getPartition("wlread"  + wlFold); // Training data for Weight Learning
		df.wlTest =  data.getPartition("wlwrite" + wlFold); // Partition to write prediction in for Weight Learning

	}

	def blockSimilarityData(config, data, df){
		def cvfold = df.cvFold;
		def wlfold = df.wlFold;
		
		TriadBlocking triadBlock = new TriadBlocking();

		log.debug("\n-------------------");
		def timeNow = new Date();
		log.debug("Fold "+ cvfold +" Start: "+timeNow);

		if (!config.createNewDataStore){
			//clearing the partitions in case already in datastore
			data.deletePartition(data.getPartition(df.wlSim));
			data.deletePartition(data.getPartition(df.cvSim));
		}
		
		df.cvSim = data.getPartition('cvsim' + cvfold);
		df.wlSim = data.getPartition('wlsim' + wlfold);

		Partition readSimilarities = data.getPartition("all_sims");
		Database getSimilarities = data.getDatabase(data.getPartition("simDummy" + cvfold), readSimilarities);

		//Dummy dbs for wl train, wl test, cv train, cv test for more involved blocking techniques - DS
		
		Database wlTrainLinks = data.getDatabase(data.getPartition("wlTrainDummy" + wlfold ), df.wlTrain);
		Database wlTestLinks = data.getDatabase(data.getPartition("wltestdummy" + wlfold), df.wlTruth);

		Database cvTrainLinks = data.getDatabase(data.getPartition("cvTrainDummy" + cvfold ), df.cvTrain);
		Database cvTestLinks = data.getDatabase(data.getPartition("cvtestdummy" + cvfold), df.cvTruth);

		Set<GroundAtom> wlTrainingLinks = Queries.getAllAtoms(wlTrainLinks, interacts);
		Set<GroundAtom> cvTrainingLinks = Queries.getAllAtoms(cvTrainLinks, interacts);
		Set<GroundAtom> wlTestingLinks = Queries.getAllAtoms(wlTestLinks, interacts);
		Set<GroundAtom> cvTestingLinks = Queries.getAllAtoms(cvTestLinks, interacts);

		wlTestingLinks.removeAll(cvTestingLinks);

		//System.out.println( wlTrainingLinks.size() +',' + wlTestingLinks.size() + ',' + cvTrainingLinks.size() + ',' + cvTestingLinks.size());

		// Reading triad target similarities from file
		for (Predicate p : [ATCSimilarity, distSimilarity, seqSimilarity, ligandSimilarity, GOSimilarity, SideEffectSimilarity, chemicalSimilarity])
		{
			log.debug("Working on " + p.getName());

			Set<GroundAtom> allSimilarities = Queries.getAllAtoms(getSimilarities, p); //SF

			def k = config.blockingNum;
			def cvBlockedSim, wlBlockedSim;
			switch(config.blockingType){
				case 1:
					cvBlockedSim = triadBlock.observedLinksInTraining(allSimilarities, cvTrainingLinks, 5, k );
					wlBlockedSim = triadBlock.observedLinksInTraining(allSimilarities, wlTrainingLinks, 5, k );
					break;
				case 2:
					cvBlockedSim = triadBlock.allLinksInTesting(allSimilarities, cvTestingLinks, 5, k );
					wlBlockedSim = triadBlock.allLinksInTesting(allSimilarities, wlTestingLinks, 5, k );
					break;
				case 3:
					cvBlockedSim = triadBlock.testTermsWithObservedLinks(allSimilarities, cvTrainingLinks, cvTestingLinks, 5, k );
					wlBlockedSim = triadBlock.testTermsWithObservedLinks(allSimilarities, wlTrainingLinks, wlTestingLinks, 5, k );
					break;

				case 4:
					cvBlockedSim = triadBlock.observedTrainWithTestTerm(allSimilarities, cvTrainingLinks, cvTestingLinks, 5, k );
					wlBlockedSim = triadBlock.observedTrainWithTestTerm(allSimilarities, wlTrainingLinks, wlTestingLinks, 5, k );
					break;
				case 5:
					cvBlockedSim = triadBlock.knnBlockingSet(true,k,0, triadBlock.GetQueryTerms(allSimilarities, 0), allSimilarities);
					wlBlockedSim = triadBlock.knnBlockingSet(true,k,0, triadBlock.GetQueryTerms(allSimilarities, 0), allSimilarities);
			}

			

			/*
			// Get the list of drugs
			def drugSet = triadBlock.GetQueryTerms(allSimilarities, 0); //SF

			// Get k similarities for those drugs
			def blockedSimilarities = triadBlock.knnBlockingSet(true, 5, 0, drugSet, allSimilarities); //SF
			*/

			// Inserting them into the partition - SF
			def cvInsert = data.getInserter(p, df.cvSim)
			def wlInsert = data.getInserter(p, df.wlSim)
			Iterator<GroundAtom> itrSimilarities = cvBlockedSim.iterator();	
			while (itrSimilarities.hasNext()){
				GroundAtom itemSimilarity = itrSimilarities.next();
				cvInsert.insertValue(itemSimilarity.getValue(), itemSimilarity.getArguments());
			}

			itrSimilarities = wlBlockedSim.iterator();	
			while (itrSimilarities.hasNext()){
				GroundAtom itemSimilarity = itrSimilarities.next();
				wlInsert.insertValue(itemSimilarity.getValue(), itemSimilarity.getArguments());
			}

		}
		getSimilarities.close(); //SF

		wlTrainLinks.close();
		wlTestLinks.close();
		cvTrainLinks.close();
		cvTestLinks.close();

	}

	def loadInteractionsData(config, data, df, isSupervisedMode){
		def cvfold = df.cvFold;
		def wlfold = df.wlFold;
		def interactions_file = 'interacts.csv'
		def interactions_ids = 'interactsids.csv'
		def interactions_positive = 'interacts_positives.csv'
		def interactions_negative = 'interacts_negatives.csv'
		
		def cvlabels = 'cvlabels' + cvfold
		def cvwrite = 'cvwrite' + cvfold
		def cvread = 'cvread' + cvfold

		def wllabels = 'wllabels' + wlfold
		def wlwrite = 'wlwrite' + wlfold
		def wlread = 'wlread' + wlfold

		log.debug("\n-------------------");
		def timeNow = new Date();
		log.debug("Fold "+ cvfold +" Start: "+timeNow);

		if (!config.createNewDataStore){
		//clearing from partition if it already exists
			data.deletePartition(data.getPartition(cvlabels));
			data.deletePartition(data.getPartition(cvwrite));
			data.deletePartition(data.getPartition(cvread));
			data.deletePartition(data.getPartition(wlwrite));
			data.deletePartition(data.getPartition(wlread));
			data.deletePartition(data.getPartition(wllabels));
		}

		// Setting up the partitions for final model
		df.cvTruth =  data.getPartition(cvlabels); // Labels for the Cross-validation hold-outs
		df.cvTest =  data.getPartition(cvwrite); // Partition to write the predictions in
		df.cvTrain =  data.getPartition(cvread); // Observed training data for the training (i.e., all data minus hold-outs)

		//Setting up the the partitions for weight learning
		df.wlTruth =  data.getPartition(wllabels); // Labels for the weight Learning
		df.wlTrain =  data.getPartition(wlread); // Training data for Weight Learning
		df.wlTest =  data.getPartition(wlwrite); // Partition to write prediction in for Weight Learning

		// Setting up the inserters
		def insertWLTrain = data.getInserter(interacts, df.wlTrain);
		def insertWLLabels = data.getInserter(interacts, df.wlTruth);
		def insertWLValid = data.getInserter(validInteraction, df.wlTrain);

		def insertCVValid = data.getInserter(validInteraction, df.cvTrain);
		def insertCVTrain = data.getInserter(interacts, df.cvTrain);
		def insertCVLabels = data.getInserter(interacts, df.cvTruth);

		def insertWLTest = data.getInserter(interacts, df.wlTest);
		def insertCVTest = data.getInserter(interacts, df.cvTest);

		// Reading the interactions and setting the data for current fold

		log.debug("\nReading INTERACTS files for fold "+ cvfold +" ");

		// Reading all the other folds as training data
		for (int j=1;j<=config.numFolds;j++)
		{
			def current_interactions_dir = config.interactions_dir + j + java.io.File.separator;
			log.debug(current_interactions_dir);
			if ((j!=cvfold) && (j!=wlfold))
			{
				
				InserterUtils.loadDelimitedData(insertWLValid, current_interactions_dir + interactions_ids);
				InserterUtils.loadDelimitedData(insertCVValid, current_interactions_dir + interactions_ids);

				if(isSupervisedMode){

					InserterUtils.loadDelimitedDataTruth(insertCVTrain, current_interactions_dir + interactions_file);
					InserterUtils.loadDelimitedDataTruth(insertWLTrain, current_interactions_dir + interactions_file);

				}else{

					InserterUtils.loadDelimitedDataTruth(insertCVTrain, current_interactions_dir + interactions_positive);
					InserterUtils.loadDelimitedDataTruth(insertWLTrain, current_interactions_dir + interactions_positive);

					//for semi-supervised setting
					InserterUtils.loadDelimitedData(insertWLTest, current_interactions_dir + interactions_negative);
					InserterUtils.loadDelimitedData(insertCVTest, current_interactions_dir + interactions_negative);
				}
			}
		}

		def train_interactions_dir = config.interactions_dir + wlfold + java.io.File.separator;

		InserterUtils.loadDelimitedData(insertWLValid, train_interactions_dir + interactions_ids);
		InserterUtils.loadDelimitedData(insertCVValid, train_interactions_dir + interactions_ids);

		// Adding the weight learning held-out to the final training data
		// Reading the weight learning held-out as the the labels - use positives file if in semi supervised mode

		if(isSupervisedMode){

			InserterUtils.loadDelimitedDataTruth(insertCVTrain, train_interactions_dir + interactions_file);

			InserterUtils.loadDelimitedDataTruth(insertWLLabels, train_interactions_dir + interactions_file);

			InserterUtils.loadDelimitedData(insertWLTest, train_interactions_dir + interactions_ids);

			

		}else{
			InserterUtils.loadDelimitedDataTruth(insertCVTrain, train_interactions_dir + interactions_positive);
			InserterUtils.loadDelimitedData(insertCVTest, train_interactions_dir + interactions_negative);
			
			InserterUtils.loadDelimitedDataTruth(insertWLLabels, train_interactions_dir + interactions_positive);
			InserterUtils.loadDelimitedData(insertWLTest, train_interactions_dir + interactions_ids);
		}

		// Reading the cross-validation held out as labels for weight learning and also into the ignored_interacts_DT.
		// Weight learning will not be able to see these labels because of they will be ignored and the body of their rules will be 0.

		def holdout_interactions_dir = config.interactions_dir + cvfold + java.io.File.separator;

		InserterUtils.loadDelimitedData(insertCVValid, holdout_interactions_dir + interactions_ids);

		// Reading the cross-validation held out as labels for the final model
		InserterUtils.loadDelimitedDataTruth(insertCVLabels, holdout_interactions_dir + interactions_file);

		//Insert drug pair ids into write partitions
		InserterUtils.loadDelimitedData(insertCVTest, holdout_interactions_dir + interactions_ids);
	}

	def bootStrappingWeightLearning(m, data, config, df, weights, de){
		def fold = df.cvFold;

		def timeNow = new Date();
		log.debug("Fold " + fold + " Bootstrapping Weight Learning: " + timeNow);

		def numIter = 3;

		for(int i = 0; i < numIter; i++){
			learnWeights(m, data, config, df, weights, 2);
			runInference(m, data, config, df, df.wlTest, df.wlTrain, df.wlSim);
			evaluateResults(data, config, df, de, df.wlTruth, df.wlTest);
			
			Database WLPredictionsDB = data.getDatabase(data.getPartition("dummy_partition"), df.wlTest);
			Set<GroundAtom> weightLearningPredictions = Queries.getAllAtoms( WLPredictionsDB, interacts );
			WLPredictionsDB.close();
			data.deletePartition(data.getPartition("dummy_partition"));

			Database wlTruthDB = data.getDatabase(data.getPartition("dummy_partition"), df.wlTruth);
			Set<GroundAtom> atomTruth = Queries.getAllAtoms(wlTruthDB, interacts);

			Set<GroundAtom> atomsToMakePositive = new HashSet<GroundAtom>();
			Set<GroundAtom> atomsToMakeNegative = new HashSet<GroundAtom>();

			int numTermsAdded = 0;

			for(GroundAtom ga: weightLearningPredictions){
				double value = ga.getValue();
				GroundTerm[] t = ga.getArguments();

				//System.out.println(ga.toString() + ": " + value);

				double truthVal = wlTruthDB.getAtom(interacts, t).getValue();

				//if(value >= 0.0 && truthVal == 0.0){
				if(value < 0.1 && truthVal == 0.0){
					wlTruthDB.deleteAtom(ga);
					atomsToMakeNegative.add(ga);
				
					numTermsAdded++;
					
				}
				if (value >= 0.5 && truthVal == 0.0){
					wlTruthDB.deleteAtom(ga);
					atomsToMakePositive.add(ga);

					numTermsAdded++;
				}
			}
			
			wlTruthDB.close();
			data.deletePartition(data.getPartition("dummy_partition"));

			
			def insertWLLabels = data.getInserter(interacts, df.wlTruth);
			def insertWLTrain = data.getInserter(interacts, df.wlTrain);
			def insertWLTest = data.getInserter(interacts, df.wlTest);
			def insertCVTrain = data.getInserter(interacts, df.cvTrain);
			
			for(GroundAtom ga: atomsToMakeNegative){
				insertWLLabels.insertValue(0.0, ga.getArguments());
				insertWLTest.insert(ga.getArguments());
				insertCVTrain.insertValue(0.0, ga.getArguments());

			}

			for(GroundAtom ga: atomsToMakePositive){
				insertWLLabels.insertValue(1.0, ga.getArguments());
				insertWLTest.insert(ga.getArguments());
				insertCVTrain.insertValue(1.0, ga.getArguments());
			}
			

			System.out.println("Number of terms added this iteration: " + numTermsAdded);

		}

	}


	def learnWeights(m,data,config,df, weights, learnerOption){ 

	    /// Weight Learning
	    // ===============
	    def fold = df.cvFold;

	    def timeNow = new Date();
	    log.debug("Fold "+ fold +" Weight Learning: " + timeNow);

	    for (CompatibilityKernel ck : Iterables.filter(m.getKernels(), CompatibilityKernel.class))
	    ck.setWeight(weights.get(ck))

	    Database dbWLTrain = data.getDatabase(df.wlTest, config.closedPredicatesInference, df.wlTrain, df.wlSim);
	    Database dbWLLabels = data.getDatabase(df.wlTruth, config.closedPredicatesTruth);

	    /*
	    Set<GroundAtom> atomLabels = Queries.getAllAtoms(dbWLLabels, interacts);
	    for(GroundAtom ga : atomLabels){
	    	System.out.println(ga.toString() + ": " + ga.getValue());
	    }
	    */

	    def wLearn;
	    switch(learnerOption) {
	    	case 1:
	    		wLearn = new MaxLikelihoodMPE(m,dbWLTrain,dbWLLabels,config.cb);
	    		break;
    		case 2:
    			wLearn = new PairedDualLearner(m,dbWLTrain,dbWLLabels,config.cb);
    			break;
			case 3:
				wLearn = new L1MaxMargin(m, dbWLTrain, dbWLLabels, config.cb);
				break;
	    }
	    
	    wLearn.learn();
	    wLearn.close();

	    

	    dbWLTrain.close();
	    dbWLLabels.close();

	    // Printing the new weights
	    println m;
	}

  // Inferring
  // =========
	  def runInference(m,data,config,df, testPartition, evidencePartition, simPartition) {
	  	def fold = df.cvFold.toString(); 
	  	def timeNow = new Date();
	  	log.debug("Fold "+ fold +" Inferring: " + timeNow);

	    Database dbCVTrain = data.getDatabase(testPartition, config.closedPredicatesInference , evidencePartition, simPartition);

	    MPEInference mpe = new MPEInference(m, dbCVTrain, config.cb);
	    FullInferenceResult result = mpe.mpeInference();
	    mpe.close();
	    mpe.finalize();
	    dbCVTrain.close();

	    timeNow = new Date();
	    log.debug("Fold "+ fold +" End: "+timeNow);
	    log.debug("-------------------");
	}


	def evaluateResults(data,config,df,de, truthPartition, predictionPartition, writeToFile){ 

	    //Begin Evaluate
	    //==============

	    def fold = df.cvFold;

	    System.out.println("Evaluating fold: " + fold.toString());

	    def labelsDB = data.getDatabase(truthPartition, config.closedPredicatesTruth)
	    Database predictionsDB = data.getDatabase(data.getPartition("dummy_cv_preds_"+fold), predictionPartition)
	    def comparator = new SimpleRankingComparator(predictionsDB);
	    comparator.setBaseline(labelsDB);

	    if(writeToFile){
	    	OutputWriter writer = new OutputWriter(predictionsDB, interacts, 'collective_'+config.interaction_type, "allFolds", "psl");
	    	writer.outputToFile(true);
	    }

	    // Choosing what metrics to report
	    def metrics = [RankingScore.AUPRC, RankingScore.NegAUPRC,  RankingScore.AreaROC]
	    double [] score = new double[metrics.size()]

	    try {
	    	for (int i = 0; i < metrics.size(); i++) {
	    		comparator.setRankingScore(metrics.get(i))
	    		score[i] = comparator.compare(interacts)
	    	}
	      //Storing the performance values of the current fold
	      de.AUPR_P_Folds[fold]=score[0];
	      de.AUPR_N_Folds[fold]=score[1];
	      de.AUC_Folds[fold]=score[2];

	      System.out.println("Area under positive-class PR curve: " + score[0])
	      System.out.println("Area under negetive-class PR curve: " + score[1])
	      System.out.println("Area under ROC curve: " + score[2])
	      System.out.println("-------------------");
	      } catch (ArrayIndexOutOfBoundsException e) {
	      	System.out.println("No evaluation data! Terminating!");
	      }

	      predictionsDB.close();
	      labelsDB.close();
	  }

	  def outputResultsCSV(interactionType, experimentName, array, resultName){
	  	BufferedWriter writer = null;
		String resultsFile = "./output/psl/" + resultName + '_' + interactionType + '_' + experimentName;
                
		try {
			writer = new BufferedWriter(new FileWriter(resultsFile));
			StringBuilder output = new StringBuilder();
			for (double d : array){
				output.append(d+',');
			}
			writer.append(output.toString());
            writer.flush();

        } catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	  }

	  def runExperiment(numDrugs, numFolds, experimentName, blockingNum, blockingType, interactionType, createDS){
	  	
	  	def config = this.setupConfig(numFolds, numDrugs, experimentName, blockingNum, blockingType, interactionType, createDS);
	    def data = this.setupDataStore(config);
	    PSLModel m = new PSLModel(this, data);
	    def weights = this.defineModel(config, data, m);
	    DrugInteractionEvalResults de = new DrugInteractionEvalResults(config.numFolds);

	    if(config.createNewDataStore){
			this.loadData(data, config);
		}

	    for(int fold = 1; fold <= config.numFolds; fold++){
	    	def wl = (fold % config.numFolds) + 1
	    	DrugInteractionFold df = new DrugInteractionFold(fold, wl);
	    	
	    	if(config.createNewDataStore){
	    		this.loadInteractionsData(config,data, df, true);
	    		this.blockSimilarityData(config, data, df);
	    	}
	    	else{
	    		this.resetDataForFold(config, data, df);
	    	}

			if(config.doWeightLearning){ 
				this.learnWeights(m,data,config,df, weights, 1);
			}
			this.runInference(m,data,config,df, df.cvTest, df.cvTrain, df.cvSim);

			this.evaluateResults(data,config,df,de, df.cvTruth, df.cvTest, true);

	  	}
	  	

	  	return de;

  	}

	  static void main(args){

	  	String[] experiments = ['all_dataset2', 'all_dataset1']
	  	//String[] experiments = ['all_dataset2']
	  	String[] interactionTypes = ['all'];

	  	//String[] experiments = ['cross_validation_subset']
	  	//String[] interactionTypes = ['crd']

	  	for(String exp: experiments){
	  		def numDrugs = 315;
	  		
	  		if(exp == "all_dataset1"){
	  			interactionTypes = ["crd", "ncrd"];
	  			numDrugs = 807;
	  		}

	  		for(String it: interactionTypes){
	  			def blockingNum = 15;
	  			def blockingType = 5;

	  			
	  			if (it == "crd"){
	  				blockingType = 3;
	  			}
	  			

	  			System.out.println("Working on interaction type " + it + " with blocking type " + blockingType);
	  			def dip = new DrugInteractionPrediction();
	  			def evaluation = dip.runExperiment(numDrugs, 10, exp, blockingNum, blockingType, it, true);
	  			
	  			dip.outputResultsCSV(it, exp, evaluation.AUC_Folds, "auc");
	  			dip.outputResultsCSV(it, exp, evaluation.AUPR_N_Folds, "auprNeg");
	  			dip.outputResultsCSV(it, exp, evaluation.AUPR_P_Folds, "auprPos");

	  		}
	  	}
  	}
}