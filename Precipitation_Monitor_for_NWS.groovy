/**
*  Precipitation and Weather Monitor for NWS Data
*  Version: v1.1
*  Download: See importUrl in definition
*  Description: Retrieves Precipitation and other information from the National Weather Service for a specific airport.
*  Intended to be used in combination with a sprinkler system to optimise the use of water.
*
*  Copyright 2022 Gary J. Milne
*
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation.
*
*
*  Precipitation Monitor - CHANGELOG
*  Version 1.0.0 - Initial public release.
*  Version 1.0.1 - Name change to 'Precipitation and Weather Monitor for NWS Data'
*  Version 1.0.2 - Fixes a bug that prevented expired records from being deleted.
*  Version 1.1.0 - Simplified some of the calculations. Accomodated a change in the URL used by the NWS for retreiving this data. Fixed bug with totals when period spans across months. Added a CheckWateringThreshold so it could be called externally if desired.
*
*  Authors Notes:
*  Known limitations: When the day is January 1st it will incorrectly calculate the day of the week for the prior day because it will use the current year instead of the prior year. Precipitation will be correct but it will be assigned to the wrong day. Something for a future release.
*
*  Gary Milne - June 8th, 2024
*
**/

import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import groovy.transform.Field

metadata {
	definition (name: "Precipitation and Weather Monitor for NWS Data", namespace: "garyjmilne", author: "Gary J. Milne", importUrl: "https://raw.githubusercontent.com/GaryMilne/Hubitat-Apps/main/Precipitation_Monitor_for_NWS.groovy", singleThreaded: true) {
        capability "Refresh"
        capability "WaterSensor"
        
		attribute "NewestRecord", "string"      //Most up to date record received. Should be equal to hour of the month.
        attribute "Day", "string"                //Day of the month
        attribute "Time", "string"               //Hour of the day
        attribute "Wind", "string"               //Wind speed and direction
        attribute "Visibility", "number"         //Visibility in miles
        attribute "Weather",  "string"           //Text description of weather
        attribute "SkyConditions", "string"      //Text description of clouds and altitude
        attribute "Dewpoint", "number"           //Dewpoint in F for US
        attribute "Humidity", "number"           //Humidity in %
        attribute "Humidity24HrAvg", "number"    //Rolling 24Hr average Humidity
        attribute "Pressure", "number"           //Pressure in millibar
        attribute "Precip-Today", "number"
        attribute "Precip-Yesterday", "number"
        attribute "Precip-Monday", "number"      //Rainfall total for Monday etc., etc.
        attribute "Precip-Tuesday", "number"
        attribute "Precip-Wednesday", "number"
        attribute "Precip-Thursday", "number"
        attribute "Precip-Friday", "number"
        attribute "Precip-Saturday", "number"
        attribute "Precip-Sunday", "number"
        attribute "Precip1Hr", "number"          //Rainfall for the hour.
        attribute "Precip3Hr", "number"          //Rolling 3Hr rainfall total etc.
        attribute "Precip6Hr", "number"
        attribute "Precip12Hr", "number"
        attribute "Precip24Hr", "number"
        attribute "Temperature", "number"        //Temperature in F for US.
        attribute "Temperature24HrAvg", "number" //Rolling 24Hr average Temperature
        attribute "water", "enum"
       
        command "clearAll", [[name:"Clears all State Variables and Current States. All calculated values for precipitation will be reset to zero. Do a browser refresh in order to see changes in State Variables."]]
        command "refresh", [[name:"Requests the latest data from NWS and adds it to the state variables. Re-calculates precip, temp and humidity stats for rolling averages. Any expired records are deleted by a call to removeExpired()."]]
        command "removeExpired", [[name:"Removes expired records from the State Variables. This function is run automatically after each update is received."]]
        command "checkWateringThreshold", [[name:"Compare the Watering Threshold against the precipitation received in the last 24 hours and set the 'water' sensor accordingly."]]
        command "test"
	}
    
	section("Configure the Inputs"){
        input name: "airportCode", type: "text", title: bold(dodgerBlue("The 4 digit ICAO Airport Code.")), description: "US airport codes all begin with K and can be found here: " + italic(dodgerBlue(" https://en.m.wikipedia.org/wiki/List_of_airports_by_ICAO_code:_K")), required:true
		input name: "retentionPeriod", type: "number", title: bold("Data Retention Period in Hours."), description: "The number of hours of data to retain. Range 48 - 168 (Default: 72)", defaultValue: 72, required:true, range: "48..168"
        input name: "wateringThreshold", type: "number", title: bold("The watering threshold in inches."), description: "If precipitation total for 'Precip24Hr' is less than the watering threshold the 'Water Sensor' is set to 'dry'. If watering threshold is exceeded by precipitation the 'Water Sensor' is set to 'wet'.", default: 0.15, required:true
        input name: "wateringThresholdCheckTime", type: "enum", title: bold("The time at which the watering threshold should be checked."), description: "Water sensor will be turned On if threshold is exceeded.", required:true,
            options: [ [0:" Never"],[1:" 1:00 AM"],[2:"  2:00 AM"],[3:"  3:00 AM"],[4:"  4:00 AM"],[5:"  5:00 AM"],[6:"  6:00 AM"] ], defaultValue: 4
        input name: "pollFrequency", type: "enum", title: bold("Poll Frequency. The frequency the website will be checked for new information."), description: "The time interval between subsequent checks of the website for new information. (Default: 1 Hour.)",
            options: [ [0:" Never"],[1:" 1/2 Hour"],[2:" 1 Hour"],[3:" 3 Hours"] ], defaultValue: 2
        input name: "detail", type: "enum", title: bold("The amount of detail to record and display."), description: "Brief: Day, Time, Precipitation, Temp and Humidity. Normal: Adds Pressure, Wind and DewPoint. Verbose: Adds Weather, SkyConditions and Visibility. " + red("Changes to this setting only apply to new records. Do a clearAll() and refresh() to repopulate with new settings."), 
            options: [ [0:" Brief"],[1:" Normal"],[2:" Verbose"]], defaultValue: 0, required:true
        input name: "loglevel", type: "enum", title: bold("The amount of information sent to the log."), description: "Increasing the log level sends additional data to the log. Used for debugging purposes. (Default: 0.)",
            options: [ [0:" Normal"],[1:" Debug"],[2:" Verbose"] ], defaultValue: 0
	}
}

//Quick test function.
def test(){
    
daily()
    
}


//**********************************************************
//**********************************************************
//*****************   Standard System Functions
//**********************************************************
//**********************************************************

//Installed gets run when the device driver is selected and saved
def installed(){
	log ("Installed", "Installed with settings: ${settings}", 0)
}

//Updated gets run when the "Save Preferences" button is clicked
def updated(){ 
    initialize()
}

//Uninstalled gets run when called from a parent app???
def uninstalled() {
    unschedule()
	log ("Uninstall", "Device uninstalled", 0)
}

//Updated gets run when the "Initialize" button is clicked or called from Updated().
def initialize(){
    //Remove any existing scheduled actions.
    unschedule()
    
    log ("initialize", "Settings updated: ${settings}", 0)

    switch(settings.pollFrequency) { 
        case "0": 
            unschedule("refresh") 
            log("initialize", "Polling has been disabled.", 0)
            break
        case "1": 
            runEvery30Minutes("refresh")
            log ("initialize", "Polling interval set to once every 30 minutes.", 0)
            break
        case "2": 
            runEvery1Hour("refresh")
            log ("initialize", "Polling interval set to once every hour.", 0)
            break
        case "3": 
            runEvery3Hours("refresh")
            log ("initialize", "Polling interval set to once every 3 hours.", 0)
            break
    }

    if (settings.wateringThresholdCheckTime == "0" ) { 
        log ("initialize", "Watering Threshold Checking has been disabled.", 0)
    }
    else {
        variable = "0 0 " + settings.wateringThresholdCheckTime + " 1/1 * ? *"
        schedule(variable, daily)
        log ("initialize", "Watering Threshold Checking will occur every day at " + settings.wateringThresholdCheckTime + ":00 AM.", 0)
    }
}


//**********************************************************
//**********************************************************
//********   Command Buttons and Scheduled Commands
//**********************************************************
//**********************************************************

//Clears all State variables and Attributes
def clearAll() {
    state.clear()
    device.deleteCurrentState("NewestRecord")
    device.deleteCurrentState("Day")
    device.deleteCurrentState("Dewpoint")
    device.deleteCurrentState("Humidity")
    device.deleteCurrentState("Precip1Hr")
    device.deleteCurrentState("Precip3Hr")
    device.deleteCurrentState("Precip6Hr")
    device.deleteCurrentState("Precip12Hr")
    device.deleteCurrentState("Precip24Hr")
    device.deleteCurrentState("Pressure")
    device.deleteCurrentState("SkyConditions")
    device.deleteCurrentState("Temperature")
    device.deleteCurrentState("Time")
    device.deleteCurrentState("Visibility")
    device.deleteCurrentState("Weather")
    device.deleteCurrentState("Wind")
    device.deleteCurrentState("Precip-Monday")
    device.deleteCurrentState("Precip-Tuesday")
    device.deleteCurrentState("Precip-Wednesday")
    device.deleteCurrentState("Precip-Thursday")
    device.deleteCurrentState("Precip-Friday")
    device.deleteCurrentState("Precip-Saturday")
    device.deleteCurrentState("Precip-Sunday")
    device.deleteCurrentState("Precip-Today")
    device.deleteCurrentState("Precip-Yesterday")
    device.deleteCurrentState("Temperature24HrAvg")
    device.deleteCurrentState("Humidity24HrAvg")
    device.deleteCurrentState("water")
    log ("clearAll", "All State Variables and Current States have been cleared.", 0)
}

//Called to run at the polling interval as set by the user preferences.
def refresh(){
    log ("refresh", "A data refresh has been initiated.", 0)
    //Update the driver with the latest data.
    getData()
    
    //Calculates the Rolling Totals from precip, temp and humidity.
    getRollingTotals()
    
    //Calculates the Precip for Yesterday. This only needs to be run once but the options are to run it every hour (overkill) or only once daily in the "daily()" function which could be missed.
    sendEvent(name: "Precip-Yesterday", value: getPrecipTotals( getDay("Yesterday") ) )
    
    //Calculates the Precip for Today so far.
    sendEvent(name: "Precip-Today", value: getPrecipTotals( getDay("Today") ) )
}
 
//Removes any expired records from the database
def removeExpired(){
    
    log ("removeExpired", "The removal of expired records has been initiated.", 0)
    
    //Generate a list of record numbers that are valid for the retention period.
    retainList = createRecordRetentionList()
    
    //Purge any records not within the retainList
    purgeExpiredRecords(retainList)
    }

//Call to run on a once daily basis.
void daily(){
    log ("daily", "The daily task has been run.", 1)
    //Compares the Precip24Hr value to the wateringThreshold and sets the status of the Water Sensor accordingly.
    checkWateringThreshold()
    
    //Do a final count for the Precipitation for Yesterday.
    result = getPrecipTotals( getDay("Yesterday") )
    
    //Now remove all expired records
    removeExpired()
}

//**********************************************************
//**********************************************************
//*****************   Date Related Functions
//**********************************************************
//**********************************************************

//Get the required information about either today or yesterday. 
def getDay(dayInfo){
    //Get Today's day of the year.
    Calendar calendar = Calendar.getInstance()
    calendar.setTime( new Date() )
    dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
    def dateInfo
    
    switch(dayInfo) { 
        case "Today": 
            dateInfo = getDateInfo ( dayOfYear )
            break
        case "Yesterday": 
            //Subtract one for yesterday
            if ( dayOfYear >= 2 ) dateInfo = getDateInfo ( dayOfYear - 1 )
            else dateInfo = getDateInfo ( 365 )
            break
    }

    return [dayName: dateInfo.dayName, dayOfMonth: dateInfo.dayOfMonth]
}

//Given the day of the year (1 - 366) this function returns information about that day.
def getDateInfo(int dayOfYear) {
    // Get the current year
    Calendar calendar = Calendar.getInstance()
    int currentYear = calendar.get(Calendar.YEAR)
    
    // Set the calendar to the specified day of the year
    calendar.set(Calendar.YEAR, currentYear)
    calendar.set(Calendar.DAY_OF_YEAR, dayOfYear)
    
    // Get the details for that day
    Date date = calendar.getTime()
    int month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-based
    int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
    int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    String[] daysofweek = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    String dayName = daysofweek[dayOfWeek - 1] // Calendar.DAY_OF_WEEK starts from 1 (Sunday)
        
    return [month: month, dayOfMonth: dayOfMonth, dayOfWeek: dayOfWeek, dayName: dayName]
}

//Calculates the hour of the month based on the present time.
def getHourOfMonthNow(){
    def date = new Date()
    def dayOfMonth = date.getAt(Calendar.DAY_OF_MONTH)
    
    int hour = date.hours
    int hourOfMonth =  dayOfMonth * 24 + hour - 24
    
    log ("getHourOfMonthNow", "getHourOfMonth: Hour of month is: $hourOfMonth", 2)
    return hourOfMonth
}

//Calculates the maximum hours for the current month and the prior month
def maxHours(){
    // First get an instance of calendar object.
    Calendar calendar = Calendar.getInstance();
    def date = new Date()
    calendar.set(date.year, date.month, date.date)
    int maxDaysThisMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    int maxHoursThisMonth = 24 * maxDaysThisMonth

    //Calendar calendar = Calendar.getInstance()
    calendar.set(Calendar.MONTH, -1)
    int maxDaysLastMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    int maxHoursLastMonth = 24 * maxDaysLastMonth
    
    log ("maxHours", "maxHoursThisMonth is: ${maxHoursThisMonth} and maxHoursLastMonth is: ${maxHoursLastMonth}", 2)
    
    return [maxHoursThisMonth: maxHoursThisMonth, maxHoursLastMonth: maxHoursLastMonth]
}

//**********************************************************
//**********************************************************
//*****************   "Database" Functions
//**********************************************************
//**********************************************************

//Tests whether a given state.variable (R-XXX) exists.
def ifRecordExists(recordNumber){
    
    def value = state."${recordNumber}"
    if (value == null ) {
        log ("ifRecordExists", "State variable: 'state.${recordNumber}' DOES NOT exist.", 2)
        return false
        }
    else {
        log ("ifRecordExists", "State variable: 'state.${recordNumber}' DOES exist.", 2)
        return true
    }
}

//Reads the weather data from the URL. Each Table Row (</tr> represents a line of data which is parsed and handed back to AddRecord() for handling.
//addRecord() checks for header information which it ignores, as well as determining whether the record already exists before attempting to add it.
def getData() {   
    log ("getData", "getData request initiated.", 1)
    def wxURI = "https://forecast.weather.gov/data/obhistory/" + settings.airportCode + ".html"
    def requestParams = [ uri:  wxURI, contentType: "text/plain" ]
    
    int count = 1        //This is the record counter
    httpGet(requestParams)
        { response ->   
        if (response?.status == 200){        //200 is an OK.
            def data = "${response.getData()}".toString()
            data.split("</tr>").each {
                row = it.replace("</td>","|")
                log ("getData", "Row data is: ${it}", 2)
                details = row.tokenize('|')
            
                //There is only 3 days of data on the website so max records is 72.
                limit = settings.retentionPeriod
                if ( limit > 72 ) limit = 72
                
                //count = 4 is the first data record.
                if (count >= 4 && count < 4 + limit ) {
                    addRecord(count, details)
                    }
                count = count + 1
                }
                return data
            }
        else {
            log.warn "getData() Error: ${response?.status}"
		    }
        }
    
	//Now clean up the records that are out of date.
    retainList = createRecordRetentionList()
    //Purge any records not within the retainList
    purgeExpiredRecords(retainList)
}

//Adds a State variable which is called R-XXX where XXX is the hour of the month. R-XXX contains all the information for a specific hour as reported by the national weather service for a particular location.
def addRecord(count, details){  
    log ("addRecord", "Received count: $count with details: $details.", 2)

    def newRecord = [:] 
    //If the record is not a properly formatted row of data we will get an exception and it will be skipped.
    try {
        //Test to see if this is a header row.  These are received more frequently via Hubitat getHTTP request than they are via a normal browser.
        isHeader = details.toString()
        isHeader = stripHTMLtags (isHeader)
        //if ( isHeader.contains("AirDwptMax") == true ) return
    
        newRecord.Day = stripHTMLtags (details[0])
        newRecord.Time = stripHTMLtags (details[1])
        newRecord.Temp = stripHTMLtags (details[6])
        precip1Hr = stripHTMLtags (details[15])
        if (precip1Hr == "") newRecord.Precip1Hr = 0
        else newRecord.Precip1Hr = precip1Hr.toFloat()
    
        //Get the Humidity and put it into a useful state.
        newRecord.Humidity = stripHTMLtags (details[10])
        newRecord.Humidity = newRecord.Humidity.replace("%", "")
    
        if ( settings.detail == "1" || settings.detail == "2"  ){
            newRecord.Dewpoint = stripHTMLtags (details[7])
            newRecord.Pressure = stripHTMLtags (details[14])    
            newRecord.Wind = stripHTMLtags (details[2])
            }
        if ( settings.detail == "2"  ){
            newRecord.Visibility = stripHTMLtags (details[3])
	        newRecord.Weather = stripHTMLtags (details[4])
	        newRecord.Sky = stripHTMLtags (details[5])
            }
    
        //Calculate the record number R-XXX from the current date and time.
        log ("addRecord", "Value of row is: ${row}.", 2)
		time = newRecord.Time
		timeDetails = time.tokenize(':')
		int hour = timeDetails[0].toInteger()
		log ("addRecord", "Count is: ${count}", 2)
		int day = newRecord.Day.toInteger()
		hourOfMonth = day * 24 + hour - 24
		variable = "R-" + padLeft(hourOfMonth)
			
		if (ifRecordExists(variable) == true ){
    	    return
            }
        else log ("addRecord", "Created record ${variable}.", 1)
		//Otherwise go ahead and the create the new State variable based on the day and hour the data was reported.
		state."${variable}" = newRecord
		
		//For each record created we should also be deleting an old record that would be 
		
		 //Record 4 is the most recent record.
		 if (count == 4) {
			sendEvent(name: "NewestRecord", value: variable)
			sendEvent(name: "Day", value: newRecord.Day)
			sendEvent(name: "Time", value: newRecord.Time)
			sendEvent(name: "Temperature", value: newRecord.Temp, Unit: "Fahrenheit")
			sendEvent(name: "Precip1Hr", value: newRecord.Precip1Hr, Unit: "Inches") 
		 }
		
		 if ( count == 4 && ( settings.detail == "1" || settings.detail == "2"  ) ){
			sendEvent(name: "Dewpoint", value: newRecord.Dewpoint, Unit : "Fahrenheit")
			sendEvent(name: "Humidity", value: newRecord.Humidity, Unit: "Percentage RH")
			sendEvent(name: "Pressure", value: newRecord.Pressure, Unit: "Millibars")
			sendEvent(name: "Wind", value: newRecord.Wind)
			}
			 
		if ( count == 4 && settings.detail == "2" ){
			sendEvent(name: "Visibility", value: newRecord.Visibility, Unit: "Miles")
			sendEvent(name: "Weather", value: newRecord.Weather)
			sendEvent(name: "SkyConditions", value: newRecord.Sky)
			}
	}
    catch (Exception e){
		log ("addRecord", "Nothing done - row did not contain valid weather data.", 1)
		}
}

//For each record created we should also be deleting an old record so that the number of retained records is <= settings.retention.
//We receive the current record number and calculate the expired record number from that.
def calcExpiredRecord(recordNumber){
    
    //This is the maximum number of hours in the current month
    def map = maxHours()
    int maxHoursThisMonth = map.maxHoursThisMonth
    int maxHoursLastMonth = map.maxHoursLastMonth
    
    detail = recordNumber.tokenize("-")
    int currentRecord = detail[1].toInteger()
    log ("calcExpiredRecord", "currentRecord is: ${currentRecord}", 1)
    
    int recordToPurge = -1
    
    //In this case we are past the early days of the month so the record we need to purge is in the same month.
    if ( (currentRecord - settings.retentionPeriod) > 0 ) {
        recordToPurge = currentRecord - settings.retentionPeriod
    }
    
    //In this case we are near the beginning of the month so the record we need to purge is in the prior month.
    if ( (currentRecord - settings.retentionPeriod) <= 0 ) {
        recordToPurge = maxHoursLastMonth - settings.retentionPeriod + currentRecord
    }
    
    log ("calcExpiredRecord", "recordToPurge is: R-${recordToPurge}", 1)
    //Return the name of the expired record based on the retention policy
    return "R-"+recordToPurge
}

//This function calculates which records should exist based upon the current hour of the month and the retention policy
//This list is then used to determine which records need to be purged during bulk operations.
def createRecordRetentionList(){
   //This is the maximum number of hours in the current month
    def map = maxHours()
    int maxRecordsThisMonth = map.maxHoursThisMonth
    int maxRecordsLastMonth = map.maxHoursLastMonth
    
    //This is the current hour of the month which represents the most recent record
    int hourOfMonth = getHourOfMonthNow()
    
    //Initialise the boundaries.    
    int lowerBound = 0
    int lowerBound2 = 0
    int upperBound = 0
    int upperBound2 = 0

    //In this case we only preserve a SINGLE consecutive set of records from "hourOfMonth" back "settings.retentionPeriod" times.
    if ( (hourOfMonth - settings.retentionPeriod) > 0 ) {
        lowerBound = hourOfMonth - settings.retentionPeriod - 1
        upperBound = hourOfMonth
    }
    
    //In this case we are near the beginning of the month so we need to preserve some records from the end of the month and some from the beginning of the new month.
    if ( (hourOfMonth - settings.retentionPeriod) <= 0 ) {
        lowerBound = 0
        upperBound = hourOfMonth
        lowerBound2 = maxRecordsLastMonth - settings.retentionPeriod + hourOfMonth - 1
        upperBound2 = maxRecordsLastMonth
    }
    
    //Now add the first set of records to be preserved are added to the list.
    def preserveList = []
    i = lowerBound
    while (i <= upperBound ) {
        variable = "R-" + padLeft(i)
        preserveList.add(variable)
        i ++
        }
  
    i = lowerBound2
    while (i <= upperBound2 ) {
        variable = "R-" + padLeft(i)
        preserveList.add(variable)
        i ++
        }

    log ("createRecordRetentionList", "PreserveList is: ${preserveList}", 2)
    return preserveList
}

//Removes any state variables whose names are not within the retainList.
//Only used for bulk operations
def purgeExpiredRecords(List retainList) {
    def keysToRemove = []
    
    //Iterate through state and determine which records need to be removed and add them to a list.
    //You cannot remove items from a list while it is being iterated.
    state.each { key, value ->
        if (!retainList.contains(key)) {
            keysToRemove.add(key)
        }
    }
    
    //Now remove the records identified as expired.
    keysToRemove.each { keyToRemove ->
        state.remove(keyToRemove)
        log ("purgeExpiredRecords", "Purged record: " + keyToRemove , 1)
    }
}



//**********************************************************
//**********************************************************
//*****************   "Statistics" Functions
//**********************************************************
//**********************************************************

//Calculates the sub-total precipitation for the prior 24 hour period.
def getRollingTotals(){
    log ("getRollingTotals", "Re-calculating the rolling totals.", 2)
    float PrecipSubTotal = 0
    float HumiditySubTotal = 0
    float TempSubTotal = 0
    int recordNumber = 0
    String recordName
    
    int hourOfMonth = getHourOfMonthNow()
    
    pauseExecution(250)
    newestRecord = device.currentValue("NewestRecord")
    detail = newestRecord.tokenize("-")
    int currentRecord = detail[1].toInteger()
    
    i = 0
    while ( i < 24 ){
        recordNumber = currentRecord - i
        recordName = "R-" + padLeft(recordNumber)
        log ("getRollingTotals", "recordName: [${i}] - ${recordName}" , 2)
         
        if ( ifRecordExists(recordName) == true ){
            def record = state."${recordName}"
            //PrecipSubTotal gets incremented every hour.
            PrecipSubTotal = (PrecipSubTotal + record.Precip1Hr).round(3)
            HumiditySubTotal = HumiditySubTotal + record.Humidity.toFloat()
            TempSubTotal = TempSubTotal + record.Temp.toFloat()
            }
        
        //This line gathers only the precip so far for today
        if ( i == (currentRecord % 24) ) {
            sendEvent(name: "Precip-Today", value: PrecipSubTotal)
            }
        if ( i == 2 && hourOfMonth >= 2 ) sendEvent(name: "Precip3Hr", value: PrecipSubTotal)
        if ( i == 5 && hourOfMonth >= 5 ) sendEvent(name: "Precip6Hr", value: PrecipSubTotal)
        if ( i == 11 && hourOfMonth >= 11 ) sendEvent(name: "Precip12Hr", value: PrecipSubTotal)
        if ( i == 23 && hourOfMonth >= 23 ){
            sendEvent(name: "Precip24Hr", value: PrecipSubTotal)
            sendEvent(name: "Temperature24HrAvg", value: (TempSubTotal/24).toInteger() )
            sendEvent(name: "Humidity24HrAvg", value: (HumiditySubTotal/24).toInteger() )
            log ("getRollingTotals", "Precip24Hr:${PrecipSubTotal}   Temperature24HrAvg:${(TempSubTotal/24).toInteger()}    Humidity24HrAvg:${(HumiditySubTotal/24).toInteger()}", 1 )
            }
        i = i +1
        }
}

//Calculates the total precipitation for a given day whether or not that day is complete.
//Note: getRollingTotals() runs at each Poll interval.
def getPrecipTotals(map){
    def recordList = []
    float PrecipTotal = 0
    recordList = createRecordRetentionList()
    recordList.each {
        if ( ifRecordExists(it) == true ){
            def record = state."${it}"
            if (record.Day.toInteger() == map.dayOfMonth.toInteger() ) {
                log ("getPrecipTotals", "Precip is: ${record.Precip1Hr}", 2)
                PrecipTotal = PrecipTotal + record.Precip1Hr
                }
            }
        }       
    
    variable = "Precip-" + map.dayName
    PrecipTotal = PrecipTotal.round(3)
    sendEvent(name: variable, value: PrecipTotal)
    log ("getPrecipTotals", "Total Precip for day ${map.dayOfMonth} is: ${PrecipTotal}", 1)
    return PrecipTotal
}

//Checks to see if the Watering Threshold has been exceeded. If it has, it turns the water sensor to wet.
void checkWateringThreshold(){  
    def Precip24Hr = device.currentValue("Precip24Hr")
    if ( Precip24Hr == null ) Precip24Hr = 0 
    
    //Refresh the Sub-Totals to make sure they are most recent data.
    getRollingTotals()
    pauseExecution(250)
    
    if ( Precip24Hr > settings.wateringThreshold.toFloat() ) {
        log ("checkWateringThreshold", "The Watering Threshold of ${settings.wateringThreshold} has been exceeded with precipitation of ${Precip24Hr} inches in the past 24 hours. Water sensor set to 'wet'.", 0)
        sendEvent(name: "water", value: "wet")
        }
    else {
        log ("checkWateringThreshold", "The Watering Threshold of ${settings.wateringThreshold} has NOT been exceeded with precipitation of ${Precip24Hr} inches in the past 24 hours. Water sensor set to 'dry'.", 0)
        sendEvent(name: "water", value: "dry")
        } 
    }


//**********************************************************
//**********************************************************
//*****************   Miscellaenous Functions 
//**********************************************************
//**********************************************************

//Pads the value with 00's to a maximum of three characters, i.e. 009, 099, 999.
String padLeft(value){
    String newValue = value.toString().padLeft(3, "0")
    return newValue
}

//Remove anything between <> HTML tags and just return the remaining text.
//Also strip CR, LF and and SPACE padding where present.
def stripHTMLtags(mystring){
    
    if ( mystring != null ) { 
        mysize = mystring.size() 
        }
    else {
        return ""
    }
    
    tagDepth = 0
    def newstring = ""
    
    //Loop through every character in the string.
    i = 0
    while (i < mysize ) {
        mychar = mystring.substring(i, i + 1)
        int code = (int)"${mychar}"
        //log.debug ("ASCII code is: " + code )
        if ( mychar == "<" ) tagDepth = tagDepth + 1
        if ( mychar == ">" ) tagDepth = tagDepth - 1
        i ++
            if (tagDepth == 0 && mychar != ">" && mychar != "\n" && mychar != "\r") {
                newstring = newstring + mychar
            }       
        }
    newstring = newstring.trim() 
    //log.info ("stripHTMLtags:  incoming string is '${mystring}' [${mystring.size()}] and returned string is '${newstring}' [${newstring.size()}] ")
    return newstring
}

String dodgerBlue(s) { return '<font color = "DodgerBlue">' + s + '</font>'}
String red(s) { return '<font color = "Red">' + s + '</font>'}
String bold(s) { return "<b>$s</b>" }
String italic(s) { return "<i>$s</i>" }
String underline(s) { return "<u>$s</u>" }



//Log status messages
private log(name, message, int loglevel){
    int threshold = 0
    //if ( settings.detail == "1" || settings.detail == "2"  ){
    if ( settings.loglevel == "1" ) threshold = 1
    if ( settings.loglevel == "2" ) threshold = 2
    
    //This is a quick way to filter out messages based on loglevel
	if ( loglevel > threshold) {return}
    if ( loglevel <= 1 ) { log.info ( "${name}(): " + message )  }
    if ( loglevel >= 2 ) { log.debug ( "${name}(): " + message ) }
}



