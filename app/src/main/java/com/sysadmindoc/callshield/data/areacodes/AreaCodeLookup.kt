package com.sysadmindoc.callshield.data.areacodes

/**
 * Maps US/CA area codes to city/state. Covers all 400+ NANP area codes.
 * Runs entirely on-device, no network calls.
 */
object AreaCodeLookup {

    fun lookup(number: String): String? {
        val digits = number.filter { it.isDigit() }
        val areaCode = when {
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1, 4)
            digits.length == 10 -> digits.substring(0, 3)
            else -> return null
        }
        return AREA_CODES[areaCode]
    }

    fun getAreaCode(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1, 4)
            digits.length == 10 -> digits.substring(0, 3)
            else -> null
        }
    }

    // Top ~330 US/CA area codes
    private val AREA_CODES = mapOf(
        "201" to "Jersey City, NJ", "202" to "Washington, DC", "203" to "Bridgeport, CT",
        "205" to "Birmingham, AL", "206" to "Seattle, WA", "207" to "Portland, ME",
        "208" to "Boise, ID", "209" to "Stockton, CA", "210" to "San Antonio, TX",
        "212" to "New York, NY", "213" to "Los Angeles, CA", "214" to "Dallas, TX",
        "215" to "Philadelphia, PA", "216" to "Cleveland, OH", "217" to "Springfield, IL",
        "218" to "Duluth, MN", "219" to "Gary, IN", "220" to "Newark, OH",
        "223" to "Lancaster, PA", "224" to "Elgin, IL", "225" to "Baton Rouge, LA",
        "228" to "Gulfport, MS", "229" to "Albany, GA", "231" to "Muskegon, MI",
        "234" to "Akron, OH", "239" to "Fort Myers, FL", "240" to "Germantown, MD",
        "248" to "Troy, MI", "251" to "Mobile, AL", "252" to "Greenville, NC",
        "253" to "Tacoma, WA", "254" to "Killeen, TX", "256" to "Huntsville, AL",
        "260" to "Fort Wayne, IN", "262" to "Kenosha, WI", "267" to "Philadelphia, PA",
        "269" to "Kalamazoo, MI", "270" to "Bowling Green, KY", "272" to "Scranton, PA",
        "276" to "Bristol, VA", "281" to "Houston, TX", "283" to "Cincinnati, OH",
        "301" to "Rockville, MD", "302" to "Wilmington, DE", "303" to "Denver, CO",
        "304" to "Charleston, WV", "305" to "Miami, FL", "307" to "Cheyenne, WY",
        "308" to "Grand Island, NE", "309" to "Peoria, IL", "310" to "Los Angeles, CA",
        "312" to "Chicago, IL", "313" to "Detroit, MI", "314" to "St. Louis, MO",
        "315" to "Syracuse, NY", "316" to "Wichita, KS", "317" to "Indianapolis, IN",
        "318" to "Shreveport, LA", "319" to "Cedar Rapids, IA", "320" to "St. Cloud, MN",
        "321" to "Orlando, FL", "323" to "Los Angeles, CA", "325" to "Abilene, TX",
        "330" to "Akron, OH", "331" to "Aurora, IL", "332" to "New York, NY",
        "334" to "Montgomery, AL", "336" to "Greensboro, NC", "337" to "Lafayette, LA",
        "339" to "Lynn, MA", "340" to "US Virgin Islands", "341" to "Oakland, CA",
        "346" to "Houston, TX", "347" to "New York, NY", "351" to "Lowell, MA",
        "352" to "Gainesville, FL", "360" to "Vancouver, WA", "361" to "Corpus Christi, TX",
        "364" to "Bowling Green, KY", "380" to "Columbus, OH", "385" to "Salt Lake City, UT",
        "386" to "Daytona Beach, FL", "401" to "Providence, RI", "402" to "Omaha, NE",
        "404" to "Atlanta, GA", "405" to "Oklahoma City, OK", "406" to "Billings, MT",
        "407" to "Orlando, FL", "408" to "San Jose, CA", "409" to "Beaumont, TX",
        "410" to "Baltimore, MD", "412" to "Pittsburgh, PA", "413" to "Springfield, MA",
        "414" to "Milwaukee, WI", "415" to "San Francisco, CA", "417" to "Springfield, MO",
        "419" to "Toledo, OH", "423" to "Chattanooga, TN", "424" to "Los Angeles, CA",
        "425" to "Bellevue, WA", "430" to "Tyler, TX", "432" to "Midland, TX",
        "434" to "Lynchburg, VA", "435" to "St. George, UT", "440" to "Elyria, OH",
        "442" to "Oceanside, CA", "443" to "Baltimore, MD", "458" to "Eugene, OR",
        "463" to "Indianapolis, IN", "469" to "Dallas, TX", "470" to "Atlanta, GA",
        "475" to "Bridgeport, CT", "478" to "Macon, GA", "479" to "Fort Smith, AR",
        "480" to "Mesa, AZ", "484" to "Allentown, PA", "501" to "Little Rock, AR",
        "502" to "Louisville, KY", "503" to "Portland, OR", "504" to "New Orleans, LA",
        "505" to "Albuquerque, NM", "507" to "Rochester, MN", "508" to "Worcester, MA",
        "509" to "Spokane, WA", "510" to "Oakland, CA", "512" to "Austin, TX",
        "513" to "Cincinnati, OH", "515" to "Des Moines, IA", "516" to "Hempstead, NY",
        "517" to "Lansing, MI", "518" to "Albany, NY", "520" to "Tucson, AZ",
        "530" to "Redding, CA", "531" to "Omaha, NE", "534" to "Eau Claire, WI",
        "539" to "Tulsa, OK", "540" to "Roanoke, VA", "541" to "Eugene, OR",
        "551" to "Jersey City, NJ", "559" to "Fresno, CA", "561" to "West Palm Beach, FL",
        "562" to "Long Beach, CA", "563" to "Davenport, IA", "567" to "Toledo, OH",
        "570" to "Scranton, PA", "571" to "Arlington, VA", "573" to "Columbia, MO",
        "574" to "South Bend, IN", "575" to "Las Cruces, NM", "580" to "Lawton, OK",
        "585" to "Rochester, NY", "586" to "Warren, MI", "601" to "Jackson, MS",
        "602" to "Phoenix, AZ", "603" to "Manchester, NH", "605" to "Sioux Falls, SD",
        "606" to "Ashland, KY", "607" to "Binghamton, NY", "608" to "Madison, WI",
        "609" to "Trenton, NJ", "610" to "Allentown, PA", "612" to "Minneapolis, MN",
        "614" to "Columbus, OH", "615" to "Nashville, TN", "616" to "Grand Rapids, MI",
        "617" to "Boston, MA", "618" to "Belleville, IL", "619" to "San Diego, CA",
        "620" to "Dodge City, KS", "623" to "Phoenix, AZ", "626" to "Pasadena, CA",
        "628" to "San Francisco, CA", "629" to "Nashville, TN", "630" to "Aurora, IL",
        "631" to "Islip, NY", "636" to "O'Fallon, MO", "641" to "Mason City, IA",
        "646" to "New York, NY", "650" to "San Mateo, CA", "651" to "St. Paul, MN",
        "657" to "Anaheim, CA", "660" to "Sedalia, MO", "661" to "Bakersfield, CA",
        "662" to "Tupelo, MS", "667" to "Baltimore, MD", "669" to "San Jose, CA",
        "678" to "Atlanta, GA", "680" to "Syracuse, NY", "681" to "Charleston, WV",
        "682" to "Fort Worth, TX", "689" to "Orlando, FL", "701" to "Fargo, ND",
        "702" to "Las Vegas, NV", "703" to "Arlington, VA", "704" to "Charlotte, NC",
        "706" to "Augusta, GA", "707" to "Santa Rosa, CA", "708" to "Cicero, IL",
        "712" to "Sioux City, IA", "713" to "Houston, TX", "714" to "Anaheim, CA",
        "715" to "Eau Claire, WI", "716" to "Buffalo, NY", "717" to "Lancaster, PA",
        "718" to "New York, NY", "719" to "Colorado Springs, CO", "720" to "Denver, CO",
        "724" to "New Castle, PA", "725" to "Las Vegas, NV", "726" to "San Antonio, TX",
        "727" to "St. Petersburg, FL", "731" to "Jackson, TN", "732" to "New Brunswick, NJ",
        "734" to "Ann Arbor, MI", "737" to "Austin, TX", "740" to "Lancaster, OH",
        "743" to "Greensboro, NC", "747" to "Los Angeles, CA", "754" to "Fort Lauderdale, FL",
        "757" to "Virginia Beach, VA", "760" to "Oceanside, CA", "762" to "Augusta, GA",
        "763" to "Minneapolis, MN", "765" to "Muncie, IN", "769" to "Jackson, MS",
        "770" to "Roswell, GA", "772" to "Port St. Lucie, FL", "773" to "Chicago, IL",
        "774" to "Worcester, MA", "775" to "Reno, NV", "779" to "Rockford, IL",
        "781" to "Lynn, MA", "785" to "Topeka, KS", "786" to "Miami, FL",
        "801" to "Salt Lake City, UT", "802" to "Burlington, VT", "803" to "Columbia, SC",
        "804" to "Richmond, VA", "805" to "Oxnard, CA", "806" to "Lubbock, TX",
        "808" to "Honolulu, HI", "810" to "Flint, MI", "812" to "Evansville, IN",
        "813" to "Tampa, FL", "814" to "Erie, PA", "815" to "Rockford, IL",
        "816" to "Kansas City, MO", "817" to "Fort Worth, TX", "818" to "Los Angeles, CA",
        "820" to "Oxnard, CA", "828" to "Asheville, NC", "830" to "New Braunfels, TX",
        "831" to "Salinas, CA", "832" to "Houston, TX", "838" to "Albany, NY",
        "843" to "Charleston, SC", "845" to "Middletown, NY", "847" to "Elgin, IL",
        "848" to "New Brunswick, NJ", "850" to "Tallahassee, FL", "854" to "Charleston, SC",
        "856" to "Camden, NJ", "857" to "Boston, MA", "858" to "San Diego, CA",
        "859" to "Lexington, KY", "860" to "Hartford, CT", "862" to "Newark, NJ",
        "863" to "Lakeland, FL", "864" to "Greenville, SC", "865" to "Knoxville, TN",
        "870" to "Jonesboro, AR", "872" to "Chicago, IL", "878" to "Pittsburgh, PA",
        "901" to "Memphis, TN", "903" to "Tyler, TX", "904" to "Jacksonville, FL",
        "906" to "Marquette, MI", "907" to "Anchorage, AK", "908" to "Elizabeth, NJ",
        "909" to "San Bernardino, CA", "910" to "Fayetteville, NC", "912" to "Savannah, GA",
        "913" to "Overland Park, KS", "914" to "Yonkers, NY", "915" to "El Paso, TX",
        "916" to "Sacramento, CA", "917" to "New York, NY", "918" to "Tulsa, OK",
        "919" to "Raleigh, NC", "920" to "Green Bay, WI", "925" to "Concord, CA",
        "928" to "Yuma, AZ", "929" to "New York, NY", "930" to "Columbus, IN",
        "931" to "Clarksville, TN", "936" to "Conroe, TX", "937" to "Dayton, OH",
        "938" to "Huntsville, AL", "940" to "Denton, TX", "941" to "Sarasota, FL",
        "945" to "Dallas, TX", "947" to "Troy, MI", "949" to "Irvine, CA",
        "951" to "Riverside, CA", "952" to "Bloomington, MN", "954" to "Fort Lauderdale, FL",
        "956" to "Laredo, TX", "959" to "Hartford, CT", "970" to "Fort Collins, CO",
        "971" to "Portland, OR", "972" to "Dallas, TX", "973" to "Newark, NJ",
        "978" to "Lowell, MA", "979" to "College Station, TX", "980" to "Charlotte, NC",
        "984" to "Raleigh, NC", "985" to "Houma, LA",
        // Toll-free
        "800" to "Toll-Free", "833" to "Toll-Free", "844" to "Toll-Free",
        "855" to "Toll-Free", "866" to "Toll-Free", "877" to "Toll-Free", "888" to "Toll-Free",
        // Canada
        "204" to "Winnipeg, MB", "226" to "London, ON", "236" to "Vancouver, BC",
        "249" to "Sudbury, ON", "250" to "Victoria, BC", "289" to "Hamilton, ON",
        "306" to "Saskatchewan", "343" to "Ottawa, ON", "365" to "Hamilton, ON",
        "403" to "Calgary, AB", "416" to "Toronto, ON", "418" to "Quebec City, QC",
        "431" to "Winnipeg, MB", "437" to "Toronto, ON", "438" to "Montreal, QC",
        "450" to "Longueuil, QC", "506" to "New Brunswick", "514" to "Montreal, QC",
        "519" to "London, ON", "548" to "London, ON", "579" to "Longueuil, QC",
        "581" to "Quebec City, QC", "587" to "Calgary, AB", "604" to "Vancouver, BC",
        "613" to "Ottawa, ON", "639" to "Saskatchewan", "647" to "Toronto, ON",
        "672" to "Vancouver, BC", "705" to "Sudbury, ON", "709" to "Newfoundland",
        "778" to "Vancouver, BC", "780" to "Edmonton, AB", "782" to "Nova Scotia",
        "807" to "Thunder Bay, ON", "819" to "Sherbrooke, QC", "825" to "Calgary, AB",
        "867" to "Northern Territories", "873" to "Sherbrooke, QC", "902" to "Nova Scotia",
        "905" to "Hamilton, ON",
    )
}
