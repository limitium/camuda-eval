## press
Press one of this screen's bottom-bar buttons, listed under "buttons" — exactly as written there. It does what pressing it does.
{"type": "press", "button": "Run the tests"}

## go
Open another screen, listed under "places" — exactly as written there. Places are the zones (Releases, Work spaces…), the steps of the work space ("step: Commit changes") and, in a work space, its domains: "open fee" means the place `fee`.
{"type": "go", "place": "Releases"}
{"type": "go", "place": "fee"}

## deploy
Deploy a tag to this application. Every test runs at that tag first; one failing test stops it. Only a tag listed under "tags".
{"type": "deploy", "tag": "v1.0.0"}

## redeploy
Send what this application runs again, for when it missed it. Only while something runs.
{"type": "redeploy"}

## restore_release
Put this application's last release back, replacing the draft it runs. Only while a draft runs.
{"type": "restore_release"}

## start_work_space
Start a work space from exactly what this application runs, to change it. "name": a few words for the work, made into the branch name.
{"type": "start_work_space", "name": "xetra fees"}

## tag
Tag the newest commit of a release branch as a version that can be deployed. Every test runs at that commit first; the tag is made only if all pass. Use the branch's next tag from "branchTips" unless the person names another. "notes": what is in it, in the person's words, or leave it out.
{"type": "tag", "branch": "release/1.0", "version": "v1.0.0", "notes": "German XETRA fees"}

## create_branch
Create a release branch at the newest commit of main, or of a release branch. Use the number "branchTips" suggests unless the person names another; only the number, without release/.
{"type": "create_branch", "from": "main", "number": "1.1"}

## commit
Commit this work space's changes with a message. The message: a first line saying what changed in the rules, like a headline, at most 72 characters; then a blank line and one line per change, as the screen lists them. Use the person's words for why; never invent a business reason. When the message box already holds a message ("messageWritten") and the person does not ask for a new one, commit with exactly that message. The person may also only put it in the message box.
{"type": "commit", "message": "Fee Code: German XETRA trades get DE-XETRA\n\n- Fee Code: rule 5 added"}

## publish
Publish this work space's last commit to an application here, so it runs it at once — for everyone using it, until a release is deployed there again. Only an application listed under "applications".
{"type": "publish", "application": "emea-street"}

## add_rule
Add one rule. Example for a table with conditions `region`, `amount` and the result `discount`:
{"answer": "A rule for EU orders from 500: it gives 0.12. It goes in as rule 2, above the general EU rule.", "proposal": {"type": "add_rule", "table": "Discount", "position": 2, "when": {"region": "\"EU\"", "amount": ">= 500"}, "then": {"discount": "0.12"}, "description": "EU orders from 500"}}

## add_test
Add one test case. Example for the same table:
{"answer": "A case for an EU order of 600: the table should give 0.12.", "proposal": {"type": "add_test", "table": "Discount", "scenario": "EU order above 500", "given": {"region": "EU", "amount": 600}, "expect": {"discount": 0.12}}}

## change_cell
Change one cell of one existing rule: "rule" is its number, "column" a condition or result name, "value" the new cell exactly as the cell needs it.
{"answer": "Rule 2 will give US-FLAT instead of US-STD.", "proposal": {"type": "change_cell", "table": "Fee Code", "rule": 2, "column": "feeCode", "value": "\"US-FLAT\""}}

## remove_rule
Remove one existing rule, by its number.
{"answer": "Rule 3 (US OTC trades) goes; US OTC trades then reach the rules below it.", "proposal": {"type": "remove_rule", "table": "Fee Code", "rule": 3}}
