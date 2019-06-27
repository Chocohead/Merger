# Merger
Plugin for [Matcher](https://github.com/sfPlayer1/Matcher) designed for merging together a pair of jars which were compiled from the same codebase but obfuscated differently.

## Usage
### Installing
Compiled releases can be downloaded from [**here**](https://github.com/Chocohead/Merger/releases). The produced jar needs to be placed in the `plugins` directory of a Matcher installation where it will be included the next time Matcher is restarted.

### Setting Up
The smaller of the two jars is best put on the A (left) side and the larger on the B (right) side. This minimises the amount of matching that has to be done (speeding the process up) and subsequently the amount of checking needed to ensure the output appears correct. Including the classpath is not strictly necessary but can improve the output when the types are used as part of the inheritance hierarchy.

When merging Minecraft client and server jars for example, the server is smaller from lacking all the client side only types such as GUIs and rendering logic, so would be on the A side and the client on B.
![Example configuration](https://i.imgur.com/auOLrTy.png)

Using the non-obfuscated patterns also aids matching from skipping types that are not obfuscated as they are not of interest. By default everything added to the input will attempt to be matched up, the common classpath jars are presumed to be completely non-obfuscated.

### Matching the Jars
The "Merging" tab contains all the logic associated with merging together the left and right sides. Each task can be executed individually, a selection run once in order, or all the tasks run up to the point where no further process is made after a pass.

![Merging tab](https://i.imgur.com/ZAllsMi.png)

Typically running the full process until complete is all that is needed before all matches are found, but the option for finer task running is still available. Manual changes can also be applied from the main Matcher GUI like normal.

### Exporting the Merge
Once the two sides have been matched sufficiently, a combined output and mapping can be produced of the result in the "Merge Exporting" tab. Additional options for detecting and correcting for inner classes, as well as recognising obfuscated libraries are planned to be implemented alongside this.

![Merge Exporting tab](https://i.imgur.com/7HBAEEY.png)

Dumping the merged jar more than once for a project could result in stale output names if the merge is changed in-between, so the Clear UIDs option allows for clearing any previously stored state so exporting acts as if done for the first time.

The location of the jar export, as well as the mapping export location and format can be configured in the export wizard:

![Export wizard](https://i.imgur.com/tJnmHMj.png)

The jar export will produce a standard `.jar` file, whilst the mappings export can be a V1 tiny file, a gz compressed V1 tiny file or a V2 tiny file. The current section headers are `server`, `client` and `glue`. The client and server are picked using the jar merge order, this impacts which annotations classes, methods and fields are annotated with when they're only present on one side (i.e. not matched) as well as which section header the obfuscated names go in. The glue names are unique identifiers for every class, method and field common between the two jars.

#### Additional Fixes
Argo fix is designed for taking in a library file which can then be matched out of the given side where it is clear the obfuscated types are part of the library. This is primarily designed around old Minecraft clients bundling the [Argo library](https://sourceforge.net/projects/argo). **Currently this feature is unimplemented on the output side**.

Fixing inner classes takes a flat class hierarchy and detects likely inner and nested classes based on the use of (likely) synthetic accessors and synthetic fields. A separate window to handle each case will open once ok is pressed. Inner only classes such as anonymous classes can be automatically resolved but nested classes can be subjective to which is likely the original root class thus manual handling is needed. **Currently this feature is unimplemented on the output side**.

#### Word of Note
Depending on the number of classes, and the similarity of the two sides, exporting can result in a small loading hang whilst the classes are merged. In cases of severe obfuscation merging can fail all together from deadlocking over retaining field or method order. Such cases should be reported as issues [here](https://github.com/Chocohead/Merger/issues) with the appropriate obfuscated jars/classes and ideally console log output. Interface ordering is not guaranteed when there are more than 3 interfaces between the left and right side to prevent this (given it is a common cause).
