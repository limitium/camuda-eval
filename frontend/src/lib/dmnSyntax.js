// What can be written in a table's cells, as this engine (Camunda 7.17, its FEEL) reads it. Every example
// here was evaluated against the engine, with the exact value it gives; where it refuses something people
// try (often because other DMN tools or spreadsheets accept it), that is said too.
// In the explanations, `backticks` mark code. Words like amount or market stand for the table's inputs.

// Condition cells: does the value in this column match? `?` is that value.
export const CONDITION_SECTIONS = [
  {
    id: 'values',
    title: 'Values',
    entries: [
      { code: '-', means: 'any value, also none (an empty cell is the same)' },
      { code: '"EU"', means: 'exactly the text EU; text is always in double quotes, and case matters' },
      { code: '"EU","UK"', means: 'any of them: a comma means or' },
      { code: 'not("EU")', means: 'anything but EU, also none' },
      { code: 'not("EU","UK")', means: 'neither EU nor UK, also none' },
      { code: '100', means: 'exactly 100; numbers have no quotes' },
      { code: '100, 200, 300', means: 'any of these numbers' },
      { code: 'true', means: 'yes; `false` for no' },
      { code: 'null', means: 'no value given' },
      { code: 'not(null)', means: 'any value given' },
      { code: '""', means: 'empty text, which is not the same as no value' },
      { code: 'home', means: 'the same value as another input, here `home`' }
    ]
  },
  {
    id: 'compare',
    title: 'Comparisons and ranges',
    entries: [
      { code: '>= 1000000', means: 'compared: `<` `<=` `>` `>=`' },
      { code: '[100..500]', means: '100 to 500, both included' },
      { code: '[100..500)', means: 'from 100, up to but not 500' },
      { code: '(100..500]', means: 'over 100, up to 500' },
      { code: '(100..500)', means: 'between 100 and 500, neither included' },
      { code: '< 0, > 100', means: 'either one: under 0 or over 100' },
      { code: '? between 1 and 5 or ? between 10 and 20', means: 'in either of two ranges, ends included' },
      { code: '? > 10 and ? < 20', means: 'both; `?` is the value being checked' },
      { code: '> limit', means: 'compared with another input, `limit`' },
      { code: '[low..high]', means: 'between two other inputs, both included' }
    ]
  },
  {
    id: 'functions',
    title: 'Checking with a function',
    note: 'Write `?` where the value being checked goes.',
    entries: [
      { code: 'starts with(?, "DE")', means: 'text starting with DE' },
      { code: 'ends with(?, "-OTC")', means: 'text ending with -OTC' },
      { code: 'contains(?, "OTC")', means: 'text with OTC anywhere in it' },
      { code: 'not(starts with(?, "DE"))', means: 'text not starting with DE; no value never matches' },
      { code: 'starts with(?, "DE") or starts with(?, "FR")', means: 'either test passes; `and` for both' },
      { code: 'matches(?, "^[A-Z]{2}$")', means: 'text matching a pattern: here exactly two capital letters' },
      { code: 'matches(?, "^de", "i")', means: 'a pattern, ignoring case: DE1 and de1 both start with de' },
      { code: 'upper case(?) = "EU"', means: 'EU written in any case: eu, Eu' },
      { code: 'string length(?) = 2', means: 'text of exactly two characters' },
      { code: 'substring(?, 1, 2) = "DE"', means: 'its first two characters are DE' },
      { code: 'even(?)', means: 'an even number; `odd(?)` for odd' },
      { code: 'modulo(?, 100) = 0', means: 'a whole multiple of 100' },
      { code: 'abs(?) > 100', means: 'more than 100 away from zero, up or down' },
      { code: '? > limit * 2', means: 'compared with a value worked out from another input' }
    ]
  },
  {
    id: 'dates',
    title: 'Dates',
    dates: true,
    note: 'A date column holds a date and a time.',
    entries: [
      { code: '>= date and time("2025-01-01")', means: 'on or after 1 January 2025' },
      { code: '< date and time("2025-01-31T12:00:00")', means: 'before noon on 31 January 2025; the time goes after the `T`' },
      { code: '[date and time("2025-01-01")..date and time("2025-02-01"))', means: 'in January 2025: from 1 January, up to but not 1 February' },
      { code: 'date(?) = date("2025-01-31")', means: 'on 31 January 2025, at any time' },
      { code: 'date(?) < today()', means: 'before today' },
      { code: 'date(?) >= today() - duration("P30D")', means: 'from 30 days ago on' },
      { code: '?.year = 2025', means: 'in 2025; also `?.month = 1`, `?.day = 31`' },
      { code: '?.weekday >= 6', means: 'on a Saturday or Sunday (Monday is 1)' },
      { code: 'day of week(?) = "Friday"', means: 'on a Friday' },
      { code: 'time(?) >= time("09:00:00")', means: 'at 9:00 or later in the day' },
      { code: '? < date and time(due)', means: 'before another date input, `due`' },
      { code: 'date(?) >= date("2025-01-01")', means: 'in a text column holding dates like 2025-01-31' }
    ]
  }
];

// Result cells: what the rule gives when it matches.
export const RESULT_SECTIONS = [
  {
    id: 'given',
    title: 'Values',
    entries: [
      { code: '"Gold"', means: 'the text Gold' },
      { code: '0.05', means: 'a number' },
      { code: 'true', means: 'yes; `false` for no' },
      { code: 'market', means: 'the value of the input `market`, unchanged' },
      { code: 'null', means: 'no value' },
      { code: '', means: 'empty: the rule gives nothing for this result' }
    ]
  },
  {
    id: 'arithmetic',
    title: 'Arithmetic',
    note: 'If an input has no value, anything worked out from it has no value either.',
    entries: [
      { code: 'amount * 0.002', means: 'worked out from an input: `+` `-` `*` `/`' },
      { code: '(amount + fee) * 2', means: 'brackets are worked out first' },
      { code: 'amount ** 2', means: 'to the power of 2: amount times amount' },
      { code: 'modulo(amount, 7)', means: 'what is left after dividing by 7: 1000 gives 6' },
      { code: 'abs(amount)', means: 'without its minus sign: -250 gives 250' },
      { code: 'max(10, amount * 0.001)', means: 'at least 10' },
      { code: 'min(amount * 0.001, 25)', means: 'at most 25' },
      { code: 'max(10, min(amount * 0.001, 25))', means: 'kept between 10 and 25' }
    ]
  },
  {
    id: 'rounding',
    title: 'Rounding',
    entries: [
      { code: 'decimal(amount * 0.0015, 2)', means: 'rounded to two places; an exact half goes to the even digit: 2.345 gives 2.34, 2.355 gives 2.36' },
      { code: 'floor(amount * 100 + 0.5) / 100', means: 'two places, an exact half always up: 2.345 gives 2.35' },
      { code: 'floor(amount * 100) / 100', means: 'cut to two places: 2.349 gives 2.34' },
      { code: 'floor(amount / 1000)', means: 'whole thousands, rounded down: 12345 gives 12' },
      { code: 'ceiling(amount / 1000)', means: 'whole thousands, rounded up: 12345 gives 13' },
      { code: 'ceiling(amount / 500) * 500', means: 'up to the next 500: 1234.5 gives 1500' },
      { code: 'decimal(amount, -2)', means: 'to the nearest hundred: 1234.5 gives 1200' }
    ]
  },
  {
    id: 'text',
    title: 'Text',
    entries: [
      { code: '"FEE-" + market', means: 'text joined together' },
      { code: '"FEE-" + string(amount)', means: 'a number put into text; without `string(…)` there is no value' },
      { code: 'string(decimal(amount, 2)) + " EUR"', means: 'a number with two places, then EUR: 2.50 EUR' },
      { code: 'upper case(market)', means: 'in capitals; `lower case(market)` for small letters' },
      { code: 'substring(code, 1, 2)', means: 'the first two characters: DE-123-X gives DE' },
      { code: 'substring(code, 4)', means: 'from the fourth character on: 123-X' },
      { code: 'substring(code, -3)', means: 'the last three characters: 3-X' },
      { code: 'substring before(code, "-")', means: 'the part before the first -: DE' },
      { code: 'substring after(code, "-")', means: 'the part after the first -: 123-X' },
      { code: 'split(code, "-")[2]', means: 'the second part between the - signs: 123; `[-1]` for the last' },
      { code: 'replace(code, "-", "")', means: 'every - taken out: DE123X' },
      { code: 'replace(code, "^ +| +$", "")', means: 'spaces at the start and the end taken out' },
      { code: 'string length(code)', means: 'how many characters: DE-123-X has 8' },
      { code: 'number(price, ",", ".")', means: 'text read as a number: 1,500.25 gives 1500.25' }
    ]
  },
  {
    id: 'choices',
    title: 'Choosing, and yes or no',
    entries: [
      { code: 'if amount > 1000 then "LARGE" else "SMALL"', means: 'picked by a condition; the `else` is required' },
      { code: 'if amount > 1000 then "L" else if amount > 100 then "M" else "S"', means: 'more than two outcomes' },
      { code: 'if market = "OTC" and amount > 1000 then 0.002 else 0.001', means: 'both must hold; `or` for either' },
      { code: 'if market in ["OTC", "EU"] then 1 else 0', means: 'one of a list' },
      { code: 'if amount between 1000 and 2000 then "MID" else "OTHER"', means: 'inside a range, ends included' },
      { code: 'if fee = null then 0 else fee', means: 'no value counts as 0' },
      { code: 'amount > 1000', means: 'in a yes/no result: true or false' },
      { code: 'market = "OTC" and amount > 1000', means: 'yes when both hold' },
      { code: 'market != "OTC"', means: 'yes when market is anything but OTC' }
    ]
  },
  {
    id: 'lists',
    title: 'Lists and lookups',
    note: 'A list is written in square brackets; positions count from 1.',
    entries: [
      { code: '[0.01, 0.02, 0.03][tier]', means: 'picked by position: tier 2 gives 0.02' },
      { code: 'get value({EU: 0.1, UK: 0.2}, market)', means: 'looked up by name: UK gives 0.2; a name not in it gives no value' },
      { code: 'market in ["EU", "UK"]', means: 'yes when market is one of them' },
      { code: 'sum([fee, tax, levy])', means: 'added up; `max([fee, tax, levy])` for the largest, `mean([fee, tax, levy])` for the average' },
      { code: 'count([fee, tax, levy][item > 6])', means: 'how many are over 6; `item` is each one in turn' },
      { code: 'some x in [fee, tax, levy] satisfies x > 6', means: 'yes when at least one is over 6; `every` for all of them' },
      { code: 'index of(["LOW", "MID", "HIGH"], level)[1]', means: 'the position of level in the list: MID gives 2' },
      { code: 'count(split(tags, ","))', means: 'how many parts between commas: a,b,c gives 3' }
    ]
  },
  {
    id: 'times',
    title: 'Dates and durations',
    dates: true,
    note: 'In a result a date input arrives as text: read it with `date and time(trade)`. In `duration("…")`: `P30D` 30 days, `P1M` a month, `P1Y` a year, `PT2H` two hours, `PT15M` 15 minutes.',
    entries: [
      { code: 'date and time("2025-01-31")', means: 'a date, in a date result; its time is midnight' },
      { code: 'date and time("2025-01-31T17:00:00")', means: 'a date with a time' },
      { code: 'date and time(trade) + duration("P30D")', means: '30 days after the date input trade' },
      { code: 'date and time(trade) + duration("P1M")', means: 'a month later; 31 January gives 28 February' },
      { code: 'date and time(trade) - duration("PT2H")', means: 'two hours earlier' },
      { code: 'date and time(trade).year', means: 'the year of a date input; also `.month`, `.day`, `.hour`' },
      { code: 'day of week(date and time(trade))', means: 'its weekday, like Friday' },
      { code: '(date and time(trade) - date and time(booked)).days', means: 'whole days from booked to trade' },
      { code: 'years and months duration(date and time(birth), today()).years', means: 'full years from birth to today: an age' },
      { code: 'date and time(today(), time("00:00:00"))', means: 'today, in a date result; `today()` alone is refused there' },
      { code: 'if date and time(trade) > date and time(booked) + duration("P1D") then "LATE" else "ON TIME"', means: 'two date inputs compared' }
    ]
  }
];

export const CONDITION_CELLS = CONDITION_SECTIONS.filter((s) => !s.dates).flatMap((s) => s.entries);
export const RESULT_CELLS = RESULT_SECTIONS.filter((s) => !s.dates).flatMap((s) => s.entries);
export const DATE_CELLS = [...CONDITION_SECTIONS, ...RESULT_SECTIONS].filter((s) => s.dates).flatMap((s) => s.entries);

// By the labels the table header uses (HIT_POLICIES), with the DMN name beside each.
export const WHEN_SEVERAL_MATCH = [
  { label: 'Only one rule may match', dmn: 'UNIQUE · U', means: 'two rules matching is an error naming both' },
  { label: 'First matching rule wins', dmn: 'FIRST · F', means: 'rules are tried top to bottom' },
  { label: 'Any may match, all must agree', dmn: 'ANY · A', means: 'matches giving different results is an error' },
  { label: 'Collect every match', dmn: 'COLLECT · C', means: 'every result, as a list; or added up (C+), counted (C#), smallest (C<), largest (C>). All but counted need a number result' },
  { label: 'Every match, in rule order', dmn: 'RULE ORDER · R', means: 'every result, as a list in rule order' },
  { label: 'Highest-priority result wins', dmn: 'PRIORITY · P', means: 'not run by this engine: a table using it cannot be evaluated', refused: true },
  { label: 'Every match, by result priority', dmn: 'OUTPUT ORDER · O', means: 'not run by this engine: a table using it cannot be evaluated', refused: true }
];

// `where` groups them on the sheet. Most are refused with an error; the ones said to give no value, or
// to match the wrong values, are worse: the engine takes them without a word.
export const REFUSED = [
  { where: 'condition', code: 'EU', instead: '"EU"', why: 'a bare word is the name of an input, and there is no input EU' },
  { where: 'condition', code: "'EU'", instead: '"EU"', why: 'single quotes are not text' },
  { where: 'condition', code: '"EU" or "UK"', instead: '"EU","UK"', why: 'never matches anything, and gives no error' },
  { where: 'condition', code: '!= "EU"', instead: 'not("EU")', why: 'a condition cannot start with != or <>; `? != "EU"` works too' },
  { where: 'condition', code: '= 100', instead: '100', why: 'no = in a condition; `==` is refused too' },
  { where: 'condition', code: '"100"', instead: '100', why: 'in a number column, text never equals a number' },
  { where: 'condition', code: '1,000', instead: '1000', why: 'no thousands separators, in any cell' },
  { where: 'condition', code: '10%', instead: '0.1', why: 'no percent sign: write the fraction' },
  { where: 'condition', code: '100..500', instead: '[100..500]', why: 'a range needs its brackets' },
  { where: 'condition', code: '100-500', instead: '[100..500]', why: 'this is 100 minus 500: it matches only -400, and gives no error' },
  { where: 'condition', code: '[1..5],[10..20]', instead: '? between 1 and 5 or ? between 10 and 20', why: 'one range per cell' },
  { where: 'condition', code: 'not([100..500])', instead: '< 100, > 500', why: 'outside a range: `not(…)` cannot hold one' },
  { where: 'condition', code: 'contains("OTC")', instead: 'contains(?, "OTC")', why: 'a function needs `?`, the value being checked' },
  { where: 'condition', code: '? = true', instead: 'true', why: 'in a yes/no column this matches false as well' },
  { where: 'condition', code: 'not(true)', instead: 'false', why: 'matches nothing at all' },
  { where: 'condition', code: 'TRUE', instead: 'true', why: 'yes and no are written in small letters' },
  { where: 'result', code: '-', instead: 'an empty cell', prose: true, why: 'in a result cell' },
  { where: 'result', code: 'JP-LRG', instead: '"JP-LRG"', why: 'without quotes this is JP minus LRG: no value, and no error' },
  { where: 'result', code: '"0.05"', instead: '0.05', why: 'in a number result this gives the text 0.05, not a number' },
  { where: 'result', code: '"FEE-" + amount', instead: '"FEE-" + string(amount)', why: 'text and a number do not join: no value, and no error' },
  { where: 'result', code: 'round(amount, 2)', instead: 'decimal(amount, 2)', why: 'there is no round; `round half up` is not known either' },
  { where: 'result', code: 'floor(amount, 2)', instead: 'floor(amount * 100) / 100', why: 'floor and ceiling take no places' },
  { where: 'result', code: 'amount % 7', instead: 'modulo(amount, 7)', why: 'no % sign' },
  { where: 'result', code: 'trim(code)', instead: 'replace(code, "^ +| +$", "")', why: 'there is no trim; `left`, `len` and `concat` are missing too' },
  { where: 'result', code: 'market == "OTC"', instead: 'market = "OTC"', why: 'one = compares' },
  { where: 'result', code: 'amount > 1000 && market = "OTC"', instead: 'amount > 1000 and market = "OTC"', why: '`&&` and `||` are written `and` and `or`' },
  { where: 'result', code: 'if amount > 1000 then "L"', instead: 'if amount > 1000 then "L" else null', why: 'every if needs an else' },
  { where: 'result', code: 'if amount in [1000..2000] then "MID" else "OTHER"', instead: 'if amount between 1000 and 2000 then "MID" else "OTHER"', why: 'a range cannot be used inside an if' },
  { where: 'date', code: 'date("2025-01-31")', instead: 'date and time("2025-01-31")', why: 'in a date column and in a date result: both hold a date and a time' },
  { where: 'date', code: '"2025-01-31"', instead: 'date and time("2025-01-31")', why: 'in a date column a date in quotes is only text' },
  { where: 'date', code: '< today()', instead: 'date(?) < today()', why: 'a date column holds a time too; `< now()` is refused as well' },
  { where: 'date', code: '[date("2025-01-01")..date("2025-01-31")]', instead: 'date(?) in [date("2025-01-01")..date("2025-01-31")]', why: 'in a date column: compare its date part' },
  { where: 'date', code: 'trade.year', instead: 'date and time(trade).year', why: 'in a result a date input is text: read it as a date first' },
  { where: 'date', code: 'date and time("2025-01-31 10:30:00")', instead: 'date and time("2025-01-31T10:30:00")', why: 'the time goes after a T, not a space; with a space there is no value, and no error' },
  { where: 'date', code: 'duration("P2W")', instead: 'duration("P14D")', why: 'weeks are not read: no value, and no error' }
];

export const REFUSED_GROUPS = [
  { where: 'condition', title: 'In condition cells' },
  { where: 'result', title: 'In result cells' },
  { where: 'date', title: 'With dates' }
];
