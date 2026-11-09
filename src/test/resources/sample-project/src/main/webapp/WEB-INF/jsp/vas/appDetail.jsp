<%@ page contentType="text/xml; charset=UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<?xml version="1.0" encoding="UTF-8"?>
<response>
  <result code="${resultCode}">OK</result>
  <country>${country}</country>
  <lang>${param.lang}</lang>
  <c:if test="${not empty appList}">
    <apps>
      <c:forEach items="${appList}" var="app" varStatus="st">
        <app id="${app}" seq="${st.index}">
          <name><c:out value="${app}"/></name>
        </app>
      </c:forEach>
    </apps>
  </c:if>
  <%@ include file="/WEB-INF/jsp/common/footer.jsp" %>
</response>
