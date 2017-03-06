'use strict';

ZafiraApp.controller('TestCasesListCtrl', [ '$scope', '$rootScope', '$http' ,'$location','UtilService', 'ProjectProvider', function($scope, $rootScope, $http, $location, UtilService, ProjectProvider) {

	$scope.UtilService = UtilService;
	$scope.testCaseId = $location.search().id;
	$scope.showReset = $scope.testCaseId != null;
	
	$scope.testCasesSearchCriteria = {
		'page' : 1,
		'pageSize' : 25
	};
	
	$scope.testsSearchCriteria = {
		'page' : 1,
		'pageSize' : 10,
		'sortOrder' : 'DESC'
	};
	
	$scope.totalResults = 0;
	$scope.testCases = [];
	$scope.tests = {};
	
	$scope.loadTestCases = function(page, pageSize){
		
		$scope.testCasesSearchCriteria.page = page;
		if(pageSize)
		{
			$scope.testCasesSearchCriteria.pageSize = pageSize;
		}
		if($scope.testCaseId)
		{
			$scope.testCasesSearchCriteria.id = $scope.testCaseId;
		}
		
		return $http.post('tests/cases/search', ProjectProvider.initProject($scope.testCasesSearchCriteria)).then(function successCallback(data) {
			var data = data.data;
			$scope.testCasesSearchCriteria.page = data.page;
			$scope.testCasesSearchCriteria.pageSize = data.pageSize;
			$scope.testCases = data.results;
			for(var i = 0; i < $scope.testCases.length; i++)
			{
				$scope.testCases[i].showDetails = false;
			}
			$scope.totalResults = data.totalResults;
			
			
		}, function errorCallback(data) {
			console.error('Failed to search test cases');
		});
	};
	
	$scope.loadTests = function(testCase){
		if(testCase.showDetails)
		{
			$scope.testsSearchCriteria.testCaseId = testCase.id;
			
			$http.post('tests/search', $scope.testsSearchCriteria).then(function successCallback(data) {
				$scope.tests[testCase.id] = data.data.results;
			}, function errorCallback(data) {
				console.error('Failed to search tests');
			});
		}
	};
	
	$scope.openPerformancePage = function(testCase) {
		$location.path('tests/cases/' + testCase.id + '/metrics');
	};
	
	$scope.resetSearchCriteria = function(){
		$scope.testCasesSearchCriteria = {
			'page' : 1,
			'pageSize' : 25
		};
		$scope.showReset = false;
		$scope.testCaseId = false;
	};
	
	$scope.getClassName = function(fullName) {
		var parts = fullName.split(".");
		return parts[parts.length - 1];
	};
	
	(function init(){
		$scope.loadTestCases(1).then(function() {
			if($scope.testCaseId)
			{
				for(var i = 0; i < $scope.testCases.length; i++)
				{
					if($scope.testCases[i].id == $scope.testCaseId)
					{
						$scope.testCases[i].showDetails = true;
						$scope.loadTests($scope.testCases[i]);
					}
				}
			}
		});
	})();
	
}]);
