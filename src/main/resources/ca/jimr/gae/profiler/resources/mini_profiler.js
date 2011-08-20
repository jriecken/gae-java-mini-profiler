/** The Mini Profiler! */
var MiniProfiler = ( function() {
  var requestData = {}, baseURL;
  /**
   * Initializes the Mini Profiler.
   */
  function init( options ) {
    baseURL = options.baseURL;

    $( document.body ).append( '<div id="@@prefix@@" style="display: none;"></div><div id="@@prefix@@-req" style="display: none;"></div>' );

    // Initialize the HTML templates
    $.template( 'requestTemplate', $( '#@@prefix@@-request-tmpl' ).html() );
    $.template( 'resultTemplate', $( '#@@prefix@@-result-tmpl' ).html() );
    $.template( 'resultTreeTemplate', $( '#@@prefix@@-result-tree-tmpl' ).html() );

    var requestIds = getRedirectRequests( window.location.href );
    requestIds.push( options.requestId );
    getProfileInformation( requestIds, 'normal' );

    // Dynamically add profile information for any Ajax requets that happen on
    // this page
    $( document ).ajaxComplete( function( e, xhr, settings ) {
      if ( xhr ) {
        var requestId = xhr.getResponseHeader( 'X-Mini-Profile-Request-Id' );
        if ( requestId ) {
          getProfileInformation( requestId.split( ',' ), "ajax" );
        }
      }
    } );

    // Display profile details when one of the request times is clicked on
    $( '#@@prefix@@' ).delegate( 'a', 'click', displayProfileDetails );
  }

  /**
   * Pulls off any request ids that were rewritten into the page url by previous
   * requests that redirected to this one.
   */
  function getRedirectRequests( url ) {
    var result = [];
    if ( url.indexOf( '?' ) >= 0 ) {
      var params = url.split( '?' )[ 1 ].split( '&' );
      for ( var i = 0; i < params.length; i++ ) {
        var param = params[ i ].split( '=' );
        if ( param[ 0 ] === '_mprid_' ) {
          result = result.concat( decodeURIComponent( param[ 1 ] ).split( ',' ) );
        }
      }
    }
    return result;
  }

  /**
   * Get profile information for the specified request id via an Ajax request.
   */
  function getProfileInformation( requestIds, type, callback ) {
    $.get( baseURL + 'results', {
      ids : requestIds.join( ',' )
    }, function( data ) {
      if ( data.ok ) {
        var requests = data.requests;
        if ( requests && requests.length ) {
          for ( var i = 0; i < requests.length; i++ ) {
            var request = requests[ i ];
            request.timestampFormatted = new Date( request.timestamp ).toString();
            // Store the request data for later
            requestData[ '@@prefix@@-req-' + request.id ] = request;
            // Add the request to the display
            $( '#@@prefix@@' ).show().append( $.tmpl( 'requestTemplate', {
              type : request.redirect ? 'redirect' : type, requestId : request.id, totalTime : ( request.profile.duration / 1000000 ).toFixed( 2 )
            } ) );
          }
        }
      }
      if ( callback ) {
        callback();
      }
    }, 'json' );
  }

  /**
   * Toggles an expand/collapse link
   */
  function toggleLinkDetails( e ) {
    e.preventDefault();
    e.stopPropagation();
    var el = $( this );
    if ( el.hasClass( 'expand' ) ) {
      $( '#' + this.id + '-d' ).slideDown();
      el.removeClass( 'expand' ).addClass( 'collapse' );
    }
    else {
      $( '#' + this.id + '-d' ).slideUp();
      el.removeClass( 'collapse' ).addClass( 'expand' );
    }
  }

  /**
   * Display detailed profile information for a request.
   */
  function displayProfileDetails( e ) {
    e.preventDefault();
    e.stopPropagation();
    var data = requestData[ this.id ];
    var resultDiv = $( '#@@prefix@@-req' );
    resultDiv.undelegate();
    resultDiv.html( $.tmpl( 'resultTemplate', data ) ).slideDown();
    resultDiv.delegate( '#@@prefix@@-req-close', 'click', function( e ) {
      e.preventDefault();
      e.stopPropagation();
      resultDiv.slideUp();
    } );
    resultDiv.delegate( '#@@prefix@@-req-profile a', 'click', toggleLinkDetails );
    resultDiv.delegate( '#@@prefix@@-req-as a', 'click', toggleLinkDetails );
  }

  return {
    init : init
  };
}() );
