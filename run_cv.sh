SCRIPT=$1
# EXP=$2
# FOLDS=$3
# INT=$5
# NDRUGS=$5

mvn compile > /dev/null
mvn dependency:build-classpath -Dmdep.outputFile=classpath.out > /dev/null
trap "exit" INT

java -Xmx120g -cp ./target/classes:`cat classpath.out` edu.ucsc.cs.$SCRIPT data