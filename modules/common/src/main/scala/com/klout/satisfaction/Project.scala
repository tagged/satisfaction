package com.klout.satisfaction

case class Project(
    name: String,
    topLevelGoals: Set[Goal],
    projectParameters: ParamMap,
    witnessGenerator: WitnessGenerator)