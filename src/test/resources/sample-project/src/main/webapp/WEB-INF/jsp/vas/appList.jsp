<%@ page language="java" contentType="text/xml; charset=UTF-8" pageEncoding="UTF-8" %>
<c:set var="installInfo" value="${installInfo}" scope="page"/>
<result>
<resultCode>${resultCode}</resultCode> 
<resultMsg>${resultMsg}</resultMsg>
<totalCount>${allCount}</totalCount>
<validCount>${validCount}</validCount>
<resultCount>${resultCount}</resultCount>
<endOfList>${endOfList}</endOfList>
<currencyInfo>
<currencyUnit>${currencyExtVO.currencySignText}</currencyUnit>
<currencyUnitPrecedes>${currencyExtVO.currencyUnitPrecedes}</currencyUnitPrecedes>
<currencyUnitDivision>${currencyExtVO.currencyUnitDivision}</currencyUnitDivision>
<digitGroupingSymbol>${currencyExtVO.digitGroupingSymbol}</digitGroupingSymbol>
<currencyUnitHasPenny>${currencyExtVO.currencyUnitHasPenny}</currencyUnitHasPenny>
<decimalSymbol>${currencyExtVO.decimalSymbol}</decimalSymbol>
</currencyInfo>
<curationInfo>
<title>${curationTitle}</title>
<description>${curationDescription}</description>
<imgURL>${curationImageUrl}</imgURL>
<contentSetID>${contentSetID}</contentSetID>
</curationInfo>
<c:forEach items="${resultVO}" var="resultVO" varStatus="status">
<appInfo>
<productID>${resultVO.contentID}</productID>
<productName><![CDATA[${resultVO.contentName}]]></productName>
<appId>${resultVO.guid}</appId>
<iconImgURL>${resultVO.imagePath}</iconImgURL>
<price>${resultVO.price}</price>
<discountPrice>${resultVO.discountPrice}</discountPrice>
<discountFlag>${resultVO.discountYN}</discountFlag>
<versionName><![CDATA[${resultVO.contentVersionTxt}]]></versionName>
<versionCode>${resultVO.contentVersionCode}</versionCode> 
<contentSize>${resultVO.fileSize}</contentSize>
<sellerName><![CDATA[${resultVO.sellerName}]]></sellerName>
<sellerURL><![CDATA[${resultVO.sellerUrl}]]></sellerURL>
<averageRating><![CDATA[${resultVO.rating}]]></averageRating>
<isLinkApp>${resultVO.linkContentYN}</isLinkApp>
<useIAP>${resultVO.IAPSupportYn}</useIAP>
<restrictedAge>${resultVO.limitAgeCode}</restrictedAge>
<c:if test="${not empty resultVO.stickerInfo}">
<stickerImgCount>${resultVO.stickerInfo.originalImgCount}</stickerImgCount>
<stickerPreviewImgURL>${resultVO.stickerInfo.previewImgURL}</stickerPreviewImgURL>
<stickerPreviewImgResolution>${resultVO.stickerInfo.previewImgResolution}</stickerPreviewImgResolution>
<stickerOriginalImgURL>${resultVO.stickerInfo.originalImgURL}</stickerOriginalImgURL>
<stickerOriginalImgResolution>${resultVO.stickerInfo.originalImgResolution}</stickerOriginalImgResolution>
<stickerAnimatedImgURL>${resultVO.stickerInfo.animatedIconImgURL}</stickerAnimatedImgURL>
</c:if>
<c:if test="${isScreenShotNeeded && empty resultVO.stickerInfo}">
<screenShotImgURL>${resultVO.scrnShtUrl}</screenShotImgURL>
<screenShotCount>${resultVO.scrnShtCnt}</screenShotCount>
<screenShotIndex>${resultVO.scrnShtIndexConcat}</screenShotIndex>
<screenShotResolution>${resultVO.scrnShtResolutionConcat}</screenShotResolution>
</c:if>
<regDate>${resultVO.productRegDate}</regDate>
<c:if test="${isDescriptionNeeded}">
<description><![CDATA[${resultVO.contentDescription}]]></description>
</c:if>
<c:if test="${resultVO.shortDescription ne null}">
<shortDescription><![CDATA[${resultVO.shortDescription}]]></shortDescription>
</c:if>
</appInfo>
</c:forEach> 
<timeInfo>${timeInfo}</timeInfo>
<serverType>${vf:getServerType()}</serverType>
</result>
